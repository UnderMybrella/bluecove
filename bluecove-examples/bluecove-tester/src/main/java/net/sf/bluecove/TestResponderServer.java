/**
 *  BlueCove - Java library for Bluetooth
 *  Copyright (C) 2006-2008 Vlad Skarzhevskyy
 * 
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *  @version $Id$
 */
package net.sf.bluecove;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Vector;

import javax.bluetooth.BluetoothStateException;
import javax.bluetooth.DataElement;
import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.LocalDevice;
import javax.bluetooth.RemoteDevice;
import javax.bluetooth.ServiceRecord;
import javax.bluetooth.ServiceRegistrationException;
import javax.bluetooth.UUID;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.io.StreamConnectionNotifier;

import net.sf.bluecove.awt.JavaSECommon;
import net.sf.bluecove.util.BluetoothTypesInfo;
import net.sf.bluecove.util.CountStatistic;
import net.sf.bluecove.util.IOUtils;
import net.sf.bluecove.util.TimeStatistic;
import net.sf.bluecove.util.TimeUtils;

public class TestResponderServer implements CanShutdown, Runnable {

	public static int countSuccess = 0;

	public static TimeStatistic allServerDuration = new TimeStatistic();

	public static FailureLog failure = new FailureLog("Server failure");

	public static int countConnection = 0;

	public static int countRunningConnections = 0;

	public static int concurrentConnectionsMax = 0;

	public Thread thread;

	private long lastActivityTime;

	private boolean stoped = false;

	boolean isRunning = false;

	public static boolean discoverable = false;

	public static long discoverableStartTime = 0;

	public static long connectorOpenTime = 0;

	private StreamConnectionNotifier serverConnection;

	private TestTimeOutMonitor monitorServer;

	private TestResponderServerL2CAP responderL2CAPServerThread = null;

	private TestResponderServerOBEX responderOBEXServer = null;

	private Vector concurrentConnectionRunnable = new Vector();

	public static CountStatistic concurrentStatistic = new CountStatistic();

	public static TimeStatistic connectionDuration = new TimeStatistic();

	private class ServerConnectionRunnable implements Runnable {

		long connectionStartTime;

		int concurrentCount = 0;

		ConnectionHolderStream c = new ConnectionHolderStream();

		boolean isRunning = true;

		private String name;

		ServerConnectionRunnable(StreamConnection conn) {
			name = "ServerConnectionTread" + (++countConnection);

			c.conn = conn;
			connectionStartTime = System.currentTimeMillis();
			synchronized (concurrentConnectionRunnable) {
				concurrentConnectionRunnable.addElement(this);
			}
		}

		String getName() {
			return this.name;
		}

		private void concurrentNotify() {
			synchronized (concurrentConnectionRunnable) {
				int concurNow = concurrentConnectionRunnable.size();
				setConcurrentCount(concurNow);
				if (concurNow > 1) {
					// Update all other working Threads
					for (Enumeration iter = concurrentConnectionRunnable.elements(); iter.hasMoreElements();) {
						ServerConnectionRunnable t = (ServerConnectionRunnable) iter.nextElement();
						t.setConcurrentCount(concurNow);
					}
				}
			}
		}

		private void setConcurrentCount(int concurNow) {
			if (concurrentCount < concurNow) {
				concurrentCount = concurNow;
			}
		}

		private void runEcho(InputStream is, char firstChar) {
			int receivedCount = 1;
			StringBuffer buf = new StringBuffer();
			char cBuf[] = new char[50];
			int cBufIdx = 0;
			boolean cBufHasBinary = false;
			buf.append(firstChar);
			cBuf[cBufIdx] = firstChar;
			cBufIdx++;
			try {
				RemoteDevice device = RemoteDevice.getRemoteDevice(c.conn);
				boolean authorized = false;
				try {
					authorized = device.isAuthorized(c.conn);
				} catch (Throwable blucoveIgnoe) {
				}
				Logger.debug("connected:" + device.getBluetoothAddress() + (device.isAuthenticated() ? " Auth" : "")
						+ (authorized ? " Authz" : "") + (device.isEncrypted() ? " Encr" : ""));

				c.os = c.conn.openOutputStream();
				c.os.write(firstChar);
				OutputStream os = c.os;
				int i;
				while ((i = is.read()) != -1) {
					receivedCount++;
					c.os.write(i);
					char c = (char) i;
					cBuf[cBufIdx] = c;
					cBufIdx++;
					if ((c == '\n') || (cBufIdx > 40)) {
						if (cBufHasBinary) {
							buf.append(" [");
							for (int k = 0; k < cBufIdx; k++) {
								buf.append(Integer.toHexString(cBuf[k])).append(' ');
							}
							buf.append("]");
						}
						Logger.debug("|" + buf.toString());
						os.flush();
						buf = new StringBuffer();
						cBufIdx = 0;
						cBufHasBinary = false;
					} else {
						buf.append(c);
						if (c < ' ') {
							cBufHasBinary = true;
						}
					}
				}
			} catch (Throwable e) {
				Logger.debug("echo error", e);
			} finally {
				if (buf.length() != 0) {
					Logger.debug("|" + buf.toString());
				}
				Logger.debug("echo received " + receivedCount);
			}
		}

		public void run() {
			int testType = 0;
			TestStatus testStatus = new TestStatus();
			TestTimeOutMonitor monitorConnection = null;
			try {
				c.is = c.conn.openInputStream();

				countRunningConnections++;
				concurrentNotify();
				if (concurrentConnectionsMax < countRunningConnections) {
					concurrentConnectionsMax = countRunningConnections;
					Logger.info("now connected:" + countRunningConnections);
				}

				int isTest = c.is.read();
				if (isTest == -1) {
					Logger.debug("EOF received");
					return;
				}
				if (isTest != Consts.SEND_TEST_START) {
					Logger.debug("not a test client connected, will echo");
					runEcho(c.is, (char) isTest);
					return;
				}
				testType = c.is.read();
				if (isTest == -1) {
					Logger.debug("EOF received");
					return;
				}
				if (testType == Consts.TEST_SERVER_TERMINATE) {
					Logger.info("Stop requested");
					shutdown();
					return;
				}
				testStatus.setName(testType);
				Logger.debug("run test# " + testType);
				monitorConnection = TestTimeOutMonitor.create("test" + testType, c, Configuration.serverTestTimeOutSec);
				c.os = c.conn.openOutputStream();
				c.active();
				CommunicationTester.runTest(testType, true, c, testStatus);
				if (!testStatus.streamClosed) {
					Logger.debug("reply OK");
					c.active();
					c.os.write(Consts.SEND_TEST_REPLY_OK);
					c.os.write(testType);
					c.os.flush();
				}
				monitorConnection.finish();
				countSuccess++;
				Logger.debug("Test# " + testType + " " + testStatus.getName() + " ok");
				if (!stoped) {
					try {
						Thread.sleep(Configuration.serverSleepB4ClosingConnection);
					} catch (InterruptedException e) {
					}
				}
			} catch (Throwable e) {
				if (!stoped) {
					failure.addFailure("test " + testType + " " + testStatus.getName(), e);
				}
				Logger.error("Test# " + testType + " " + testStatus.getName() + " error", e);
			} finally {
				if (monitorConnection != null) {
					monitorConnection.finish();
				}
				synchronized (concurrentConnectionRunnable) {
					concurrentConnectionRunnable.removeElement(this);
				}
				countRunningConnections--;
				concurrentStatistic.add(concurrentCount);
				connectionDuration.add(TimeUtils.since(connectionStartTime));

				IOUtils.closeQuietly(c.is);
				IOUtils.closeQuietly(c.os);
				IOUtils.closeQuietly(c.conn);
				isRunning = false;
				synchronized (this) {
					notifyAll();
				}
			}
			Logger.info("*Test Success:" + countSuccess + " Failure:" + failure.countFailure);
		}

	}

	public TestResponderServer() throws BluetoothStateException {
		TestResponderCommon.startLocalDevice();
	}

	public void run() {
		stoped = false;
		isRunning = true;
		if (!Configuration.serverContinuous) {
			lastActivityTime = System.currentTimeMillis();
			monitorServer = TestTimeOutMonitor.create("ServerUp", this, Consts.serverUpTimeOutSec);
		}
		try {
			LocalDevice localDevice = LocalDevice.getLocalDevice();
			if ((localDevice.getDiscoverable() == DiscoveryAgent.NOT_DISCOVERABLE)
					|| (Configuration.testServerForceDiscoverable)) {
				if (!setDiscoverable()) {
					return;
				}
			}

			if (Configuration.testRFCOMM.booleanValue()) {
				serverConnection = (StreamConnectionNotifier) Connector.open(BluetoothTypesInfo.PROTOCOL_SCHEME_RFCOMM
						+ "://localhost:" + Configuration.blueCoveUUID() + ";name=" + Consts.RESPONDER_SERVERNAME
						+ "_rf" + Configuration.serverURLParams());

				connectorOpenTime = System.currentTimeMillis();
				Logger.info("ResponderServer started " + TimeUtils.timeNowToString());
				if (Configuration.testServiceAttributes.booleanValue()) {
					ServiceRecord record = LocalDevice.getLocalDevice().getRecord(serverConnection);
					if (record == null) {
						Logger.warn("Bluetooth ServiceRecord is null");
					} else {
						String initial = BluetoothTypesInfo.toString(record);
						boolean printAllVersion = true;
						if (printAllVersion) {
							Logger.debug("ServiceRecord\n" + initial);
						}
						buildServiceRecord(record);
						try {
							localDevice.updateRecord(record);
							Logger.debug("ServiceRecord updated\n" + BluetoothTypesInfo.toString(record));
						} catch (Throwable e) {
							if (!printAllVersion) {
								Logger.debug("ServiceRecord\n" + initial);
							}
							Logger.error("Service Record update error", e);
						}
					}
				}
			}

			if (Configuration.supportL2CAP) {
				if (Configuration.testL2CAP.booleanValue()) {
					responderL2CAPServerThread = TestResponderServerL2CAP.startServer();
				}
			} else {
				Logger.info("No L2CAP support");
			}
			if (Configuration.testRFCOMM.booleanValue()) {
				try {
					responderOBEXServer = TestResponderServerOBEX.startServer();
				} catch (Throwable noObex) {
					Logger.error("OBEX Service ", noObex);
				}
			}

			if (Configuration.testRFCOMM.booleanValue()) {
				boolean showServiceRecordOnce = true;
				while ((Configuration.testRFCOMM.booleanValue()) && (!stoped)) {
					if ((countConnection % 5 == 0) && (Configuration.testServiceAttributes.booleanValue())) {
						// Problems on SE
						// updateServiceRecord();
					}
					Logger.info("Accepting RFCOMM connections");
					if (showServiceRecordOnce) {
						Logger.debug("Url:"
								+ LocalDevice.getLocalDevice().getRecord(serverConnection).getConnectionURL(
										ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false));
					}
					StreamConnection conn = serverConnection.acceptAndOpen();
					if (!stoped) {
						Logger.info("Received RFCOMM connection");
						if (countConnection % 5 == 0) {
							Logger.debug("Server up time " + TimeUtils.secSince(connectorOpenTime));
							Logger.debug("max concurrent con " + concurrentConnectionsMax);
						}
						if (showServiceRecordOnce) {
							Logger.debug("ServiceRecord\n"
									+ BluetoothTypesInfo.toString(LocalDevice.getLocalDevice().getRecord(
											serverConnection)));
							showServiceRecordOnce = false;
						}
						lastActivityTime = System.currentTimeMillis();
						ServerConnectionRunnable r = new ServerConnectionRunnable(conn);
						Thread t = Configuration.cldcStub.createNamedThread(r, r.getName());
						t.start();
						if (!Configuration.serverAcceptWhileConnected) {
							while (r.isRunning) {
								synchronized (r) {
									try {
										t.wait();
									} catch (InterruptedException e) {
										break;
									}
								}
							}
						}
					} else {
						IOUtils.closeQuietly(conn);
					}
					Switcher.yield(this);
				}
				closeServer();
			}

		} catch (Throwable e) {
			if (!stoped) {
				Logger.error("RFCOMM Server start error", e);
			}
		} finally {
			Logger.info("RFCOMM Server finished! " + TimeUtils.timeNowToString());
			isRunning = false;
		}
		if (monitorServer != null) {
			monitorServer.finish();
		}
	}

	public boolean isRunning() {
		return isRunning || ((responderL2CAPServerThread != null) && responderL2CAPServerThread.isRunning())
				|| ((responderOBEXServer != null) && (responderOBEXServer.isRunning()));
	}

	public static long avgServerDurationSec() {
		return allServerDuration.avgSec();
	}

	public boolean hasRunningConnections() {
		return (countRunningConnections > 0);
	}

	public long lastActivityTime() {
		return lastActivityTime;

	}

	public static void clear() {
		countSuccess = 0;
		countConnection = 0;
		concurrentConnectionsMax = 0;
		allServerDuration.clear();
		failure.clear();
		concurrentStatistic.clear();
		connectionDuration.clear();
	}

	private void closeServer() {
		if (serverConnection != null) {
			synchronized (this) {
				try {
					if (serverConnection != null) {
						serverConnection.close();
					}
					Logger.debug("serverConnection closed");
				} catch (Throwable e) {
					Logger.error("Server stop error", e);
				}
			}
			serverConnection = null;
		}
		TestResponderServerL2CAP t = responderL2CAPServerThread;
		responderL2CAPServerThread = null;
		if (t != null) {
			t.closeServer();
		}
		if (responderOBEXServer != null) {
			responderOBEXServer.closeServer();
			responderOBEXServer = null;
		}

		setNotDiscoverable();
	}

	public static boolean setDiscoverable() {
		return setDiscoverable(DiscoveryAgent.GIAC);
	}

	public static boolean setDiscoverable(int mode) {
		try {
			LocalDevice localDevice = LocalDevice.getLocalDevice();
			boolean rc = localDevice.setDiscoverable(mode);
			String modeStr;
			if (DiscoveryAgent.GIAC == mode) {
				modeStr = "GIAC";
			} else if (DiscoveryAgent.LIAC == mode) {
				modeStr = "LIAC";
			} else {
				modeStr = "0x" + Integer.toHexString(mode);
			}

			if (!rc) {
				Logger.error("Set Discoverable " + modeStr + " " + rc);
			} else {
				Logger.debug("Set Discoverable " + modeStr + " " + rc);
			}
			discoverable = true;
			discoverableStartTime = System.currentTimeMillis();
			return true;
		} catch (Throwable e) {
			Logger.error("Start server error", e);
			return false;
		}
	}

	public static void setNotDiscoverable() {
		try {
			allServerDuration.add(TimeUtils.since(discoverableStartTime));
			LocalDevice localDevice = LocalDevice.getLocalDevice();
			localDevice.setDiscoverable(DiscoveryAgent.NOT_DISCOVERABLE);
			Logger.debug("Set Not Discoverable");
			discoverable = false;
		} catch (Throwable e) {
			Logger.error("Stop server error", e);
		}
	}

	public void shutdown() {
		Logger.info("shutdownServer");
		stoped = true;
		if (Configuration.cldcStub != null) {
			Configuration.cldcStub.interruptThread(thread);
		}
		closeServer();
	}

	public void updateServiceRecord() {
		if (serverConnection == null) {
			return;
		}
		try {
			ServiceRecord record = LocalDevice.getLocalDevice().getRecord(serverConnection);
			if (record != null) {
				updateVariableServiceRecord(record);
				LocalDevice.getLocalDevice().updateRecord(record);
			}
		} catch (Throwable e) {
			Logger.error("updateServiceRecord", e);
		}
	}

	static void updateVariableServiceRecord(ServiceRecord record) {
		// long data;
		//		
		// Calendar calendar = Calendar.getInstance();
		// calendar.setTime(new Date());
		// data = 1 + calendar.get(Calendar.MINUTE);
		//        
		// record.setAttributeValue(Consts.VARIABLE_SERVICE_ATTRIBUTE_BYTES_ID,
		// new DataElement(DataElement.U_INT_4, data));
	}

	static void buildServiceRecord(ServiceRecord record) throws ServiceRegistrationException {
		String id = "";
		try {
			if (Configuration.testAllServiceAttributes.booleanValue()) {
				id = "all";
				ServiceRecordTester.addAllTestServiceAttributes(record);
				return;
			}

			id = "pub";
			buildServiceRecordPub(record);
			id = "int";
			record.setAttributeValue(Consts.TEST_SERVICE_ATTRIBUTE_INT_ID, new DataElement(
					Consts.TEST_SERVICE_ATTRIBUTE_INT_TYPE, Consts.TEST_SERVICE_ATTRIBUTE_INT_VALUE));
			id = "long";
			record.setAttributeValue(Consts.TEST_SERVICE_ATTRIBUTE_LONG_ID, new DataElement(
					Consts.TEST_SERVICE_ATTRIBUTE_LONG_TYPE, Consts.TEST_SERVICE_ATTRIBUTE_LONG_VALUE));
			if (!Configuration.testIgnoreNotWorkingServiceAttributes.booleanValue()) {
				id = "str";
				record.setAttributeValue(Consts.TEST_SERVICE_ATTRIBUTE_STR_ID, new DataElement(DataElement.STRING,
						Consts.TEST_SERVICE_ATTRIBUTE_STR_VALUE));
			}
			id = "url";
			record.setAttributeValue(Consts.TEST_SERVICE_ATTRIBUTE_URL_ID, new DataElement(DataElement.URL,
					Consts.TEST_SERVICE_ATTRIBUTE_URL_VALUE));

			id = "bytes";
			record.setAttributeValue(Consts.TEST_SERVICE_ATTRIBUTE_BYTES_ID, new DataElement(
					Consts.TEST_SERVICE_ATTRIBUTE_BYTES_TYPE, Consts.TEST_SERVICE_ATTRIBUTE_BYTES_VALUE));

			id = "variable";
			updateVariableServiceRecord(record);

			id = "info";
			record.setAttributeValue(Consts.SERVICE_ATTRIBUTE_BYTES_SERVER_INFO, new DataElement(DataElement.URL,
					ServiceRecordTester.getBTSystemInfo()));

			id = "update";
			// LocalDevice.getLocalDevice().updateRecord(record);

		} catch (Throwable e) {
			Logger.error("ServiceRecord " + id, e);
		}
	}

	static void setAttributeValue(ServiceRecord record, int attrID, DataElement attrValue) {
		try {
			if (!record.setAttributeValue(attrID, attrValue)) {
				Logger.error("SrvReg attrID=" + attrID);
			}
		} catch (Exception e) {
			Logger.error("SrvReg attrID=" + attrID, e);
		}
	}

	static void buildServiceRecordPub(ServiceRecord record) throws ServiceRegistrationException {
		final short UUID_PUBLICBROWSE_GROUP = 0x1002;
		final short ATTR_BROWSE_GRP_LIST = 0x0005;
		// Add the service to the 'Public Browse Group'
		DataElement browseClassIDList = new DataElement(DataElement.DATSEQ);
		UUID browseClassUUID = new UUID(UUID_PUBLICBROWSE_GROUP);
		browseClassIDList.addElement(new DataElement(DataElement.UUID, browseClassUUID));
		setAttributeValue(record, ATTR_BROWSE_GRP_LIST, browseClassIDList);
	}

	public static void main(String[] args) {
		JavaSECommon.initOnce();
		try {
			(new TestResponderServer()).run();
			if (TestResponderServer.failure.countFailure > 0) {
				System.exit(1);
			} else {
				System.exit(0);
			}
		} catch (Throwable e) {
			Logger.error("start error ", e);
			System.exit(1);
		}
	}

}
