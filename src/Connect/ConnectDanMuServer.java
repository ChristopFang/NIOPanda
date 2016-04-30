package Connect;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.mysql.jdbc.Connection;
import com.mysql.jdbc.PreparedStatement;

import Server.HttpRequest;

public class ConnectDanMuServer {

	final static byte[] STARTFLAG = { 0x00, 0x06, 0x00, 0x02 }; // 连接弹幕服务器帧头
	final static byte[] RESPONSE = { 0x00, 0x06, 0x00, 0x06 }; // 连接弹幕服务器响应
	final static byte[] KEEPALIVE = { 0x00, 0x06, 0x00, 0x00 }; // 与弹幕服务器心跳心跳保持
	final static byte[] RECEIVEMSG = { 0x00, 0x06, 0x00, 0x03 }; // 接收到消息
	final static byte[] HEARTBEATRESPONSE = { 0x00, 0x06, 0x00, 0x01 };// 心跳保持服务器返回的值
	final static String DANMUSERVERURL = "http://www.panda.tv/ajax_chatinfo";
	final static int IGNOREBYTELENGTH = 16;// 弹幕消息体忽略的字节数

	final static int MAX_AUTO_CONNECT_TIME = 5;// 自动断线重连次数

	private int mRid;
	private int mAppid;
	private String mTs;
	private String mSign;
	private String mAuthtype;

	private String mIP;
	private int mPort;

	private int mErrno;
	private String mErrMsg;

	//private Socket socket = null;
	private SocketChannel socChannel;
	private DataOutputStream os = null;
	private DataInputStream is = null;
	private volatile boolean mIsHeartBeatThreadStop = true;
	private volatile boolean mIsReceivMsgThreadStop = true;

	private String mRoomID;
	private boolean mIsSendHeartbeatPack = false;

	// private PandaTVDanmu mUIFrame = null;

	public int getmRid() {
		return mRid;
	}

	public void setmRid(int mRid) {
		this.mRid = mRid;
	}

	public int getmAppid() {
		return mAppid;
	}

	public void setmAppid(int mAppid) {
		this.mAppid = mAppid;
	}

	public String getmTs() {
		return mTs;
	}

	public void setmTs(String mTs) {
		this.mTs = mTs;
	}

	public String getmSign() {
		return mSign;
	}

	public void setmSign(String mSign) {
		this.mSign = mSign;
	}

	public String getmAuthtype() {
		return mAuthtype;
	}

	public void setmAuthtype(String mAuthtype) {
		this.mAuthtype = mAuthtype;
	}

	public String getmIP() {
		return mIP;
	}

	public void setmIP(String mIP) {
		this.mIP = mIP;
	}

	public int getmPort() {
		return mPort;
	}

	public void setmPort(int mPort) {
		this.mPort = mPort;
	}

	public int getmErrno() {
		return mErrno;
	}

	public void setmErrno(int mErrno) {
		this.mErrno = mErrno;
	}

	public String getmErrMsg() {
		return mErrMsg;
	}

	public void setmErrMsg(String mErrMsg) {
		this.mErrMsg = mErrMsg;
	}

	// public ConnectDanMuServer(PandaTVDanmu frame) {
	// mUIFrame = frame;
	// }

	// public ConnectDanMuServer(JSONObject json) throws JSONException {
	// JSONObject jsonData = (JSONObject) json.get("data");
	// JSONArray jsonArray = jsonData.getJSONArray("chat_addr_list");
	// String danMuAddress = jsonArray.getString(0);
	// mIP = danMuAddress.split(":")[0];
	// mPort = Integer.parseInt(danMuAddress.split(":")[1]);
	// mRid = (int) jsonData.get("rid");
	// mAppid = (int) jsonData.get("appid");
	// mAuthtype = jsonData.getString("authtype");
	// mSign = jsonData.getString("sign");
	// mTs = jsonData.getString("ts");
	// mErrno = json.getInt("errno");
	// mErrMsg = json.getString("errmsg");
	// }

	public boolean JsonDecode(JSONObject json) {
		try {
			JSONObject jsonData = (JSONObject) json.get("data");
			JSONArray jsonArray = jsonData.getJSONArray("chat_addr_list");
			String danMuAddress = jsonArray.getString(0);
			mIP = danMuAddress.split(":")[0];
			mPort = Integer.parseInt(danMuAddress.split(":")[1]);
			mRid = (int) jsonData.get("rid");
			mAppid = (int) jsonData.get("appid");
			mAuthtype = jsonData.getString("authtype");
			mSign = jsonData.getString("sign");
			mTs = jsonData.getString("ts");
			mErrno = json.getInt("errno");
			mErrMsg = json.getString("errmsg");
		} catch (JSONException e) {
			return false;
		} catch (ClassCastException e) {
			return false;
		}

		return true;
	}

	/*
	 * 获取建立连接需要发送的数据
	 */
	public byte[] GetConnectData() {
		String danMUMsg = "u:" + mRid + "@" + mAppid + "\nk:1\nt:300\nts:" + mTs + "\nsign:" + mSign + "\nauthtype:"
				+ mAuthtype;
		byte content[] = danMUMsg.getBytes();
		byte length[] = { (byte) (content.length >> 8), (byte) (content.length & 0xff) };
		byte sendMessage[] = new byte[STARTFLAG.length + 2 + content.length];
		System.arraycopy(STARTFLAG, 0, sendMessage, 0, STARTFLAG.length);
		System.arraycopy(length, 0, sendMessage, STARTFLAG.length, length.length);
		System.arraycopy(content, 0, sendMessage, STARTFLAG.length + length.length, content.length);
		return sendMessage;
	}

	public boolean ConnectToDanMuServer(String roomID) {
		mRoomID = roomID;
		boolean isSuccess = false;
		String result = "";
		JSONObject json;
		result = HttpRequest.sendGet(DANMUSERVERURL, "roomid="+roomID);// 发送http请求，获取基本参数
		if (result == null)
			return false;
		try {
			json = new JSONObject(result);// 构建json对象
		} catch (JSONException e) {
			return false;
		}
		if (!JsonDecode(json))// 将json内容解析到成员变量中
			return false;
		// 发送连接弹幕服务器请求（通过socket）
		try {
			//socket = new Socket(mIP, mPort);// 建立socket连接
			socChannel=SocketChannel.open();
			socChannel.connect(new InetSocketAddress(mIP,mPort));
			//os = new DataOutputStream(socket.getOutputStream());// 构建输入输出流对象
			//is = new DataInputStream(socket.getInputStream());
//			SocketChannel socketChannel = socket.getChannel();
//			ByteBuffer buffer = ByteBuffer.wrap(GetConnectData());
//			if(buffer==null)
//				return false;
//			socketChannel.write(buffer);
//			buffer.clear();
			os.write(GetConnectData());// 发送
			//os.flush();
			// 接收响应数据
			if (socChannel != null && os != null && is != null && socChannel.isConnected()){
				byte readData[] = new byte[6];
				int rpLength = is.read(readData);
				if (rpLength >= 6) {
					if (!(readData[0] == RESPONSE[0] && readData[1] == RESPONSE[1] && readData[2] == RESPONSE[2]
							&& readData[3] == RESPONSE[3]))
						isSuccess = false;
					else {
						isSuccess = true;
						// 消息主体，暂时用不到
					/*
					 * short dataLength=(short) (readData[5]|(readData[4]<<8));
					 * byte[] data=new byte[dataLength];//数据
					 * is.read(data);//主体数据，appid+r的值，eg:id:845694055\nr:0 暂时用不到
					 */
					}
				} else
					isSuccess = false;
			}else{
				isSuccess=false;
			}

		} catch (NumberFormatException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if (isSuccess) {
			// 心跳保持
			HeartBeat heartBeat = new HeartBeat();
			Thread heartBeatThread = new Thread(heartBeat);// 为心跳保持创建新的线程
			mIsHeartBeatThreadStop = false;
			//设置成当主线程停止时子线程停止
			//heartBeatThread.setDaemon(true);
			heartBeatThread.start();// 开始线程
			// 接收弹幕消息
			ReceiveMessage ReceiveMsg = new ReceiveMessage();
			Thread receiveMsgThread = new Thread(ReceiveMsg);
			mIsReceivMsgThreadStop = false;
			//receiveMsgThread.setDaemon(true);
			receiveMsgThread.start();
			return true;
		}
		return false;
	}

	// 实现Runnable的内，用来作为一个新线程与弹幕服务器保持连接（心跳）
	private class HeartBeat implements Runnable {
		byte[] heartBeatRsponse = new byte[4];
		int autoConnectedTime = 0;

		@Override
		public void run() {
			while (!mIsHeartBeatThreadStop) {
				try {
					//os.write(KEEPALIVE);
					ByteBuffer buffer = ByteBuffer.wrap(KEEPALIVE);
					socChannel.write(buffer);
					buffer.clear();
					if (mIsSendHeartbeatPack) {// 没有接收到响应，已经与服务器断开了
						// 连接断开,自动重新连接
						ConnectToDanMuServer(mRoomID);
						++autoConnectedTime;
						if (autoConnectedTime > MAX_AUTO_CONNECT_TIME) {// 超过最大断线重连次数
							// mUIFrame.UpdateDanMu(new Danmu(1, 1, "", "30",
							// "", "", "重连次数达到最大！", "", "1", ""));
							autoConnectedTime = 0;
							// mUIFrame.CloseConnection();
						} else {
							try {
								//Thread.currentThread().sleep(2000);
								Thread.sleep(2000);
							} catch (InterruptedException e) {
								// TODO Auto-generated catch block
								e.printStackTrace();
							}
							continue;
						}
					}
					autoConnectedTime = 0;// 能走到这里，表示连接正常，所以对断线重连计数置零
					mIsSendHeartbeatPack = true;// 标志已经发送给了服务器心跳包
					// System.out.println("心跳");
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				try {
					Thread.sleep(300000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			System.out.println("HeartBeat Close");
		}

	}

	/*
	 * 用来接收消息的新线程
	 */
	private class ReceiveMessage implements Runnable {

		@Override
		public void run() {
			byte[] receivMsgFlag = new byte[4];
			short msgLength;
			byte[] ignoreBytes = new byte[IGNOREBYTELENGTH];
			int mssageLength = 0;
			while (!mIsReceivMsgThreadStop) {
				try {
					if (is.read(receivMsgFlag) >= 4) {// 接收到消息
						if (receivMsgFlag[0] == RECEIVEMSG[0] && receivMsgFlag[1] == RECEIVEMSG[1]
								&& receivMsgFlag[2] == RECEIVEMSG[2] && receivMsgFlag[3] == RECEIVEMSG[3]) {// 接收到弹幕消息

							// System.out.println("消息"+(count++));
							msgLength = is.readShort();
							if (msgLength > 0) {// 接收消息体长度
								byte[] rcvMsg = new byte[msgLength];
								is.read(rcvMsg);

								mssageLength = is.readInt();// 获取后面消息的长度
								is.read(ignoreBytes);
								mssageLength -= IGNOREBYTELENGTH;// 剩下的信息长度减去
								if (mssageLength > 0) {
									byte[] msg = new byte[mssageLength];// 存放消息体
									is.read(msg);// 读消息体,msg即为弹幕消息体，格式为json格式
									// 解析消息体
									MessageDecode(new String(msg, "UTF-8"));
								}
							}
						} else if (receivMsgFlag[0] == HEARTBEATRESPONSE[0] && receivMsgFlag[1] == HEARTBEATRESPONSE[1]
								&& receivMsgFlag[2] == HEARTBEATRESPONSE[2]
								&& receivMsgFlag[3] == HEARTBEATRESPONSE[3]) {
							// 连接正常
							mIsSendHeartbeatPack = false; // 收到服务器对心跳包的响应，标志复位
						} else {

						}
					}

				} catch (IOException e) {
					// TODO Auto-generated catch block
					// e.printStackTrace();
					System.out.println("ReceiveMessage closed after socket closed:\n" + e);
				}
			}
			System.out.println("ReceiveMessage Close");
		}
	}

	Message messageHelper = new Message();
	Object messageReceied;
	/*
	 * 解读消息体，注意！！在弹幕多的情况下，一条msg中可能有多条弹幕信息
	 */
	public void MessageDecode(String messageStr) {
		// 将这个json格式的字符串进行处理，获得相应类型的对象
		int indexOfStrEnd;
		indexOfStrEnd = messageStr.indexOf("{");
		if(!messageStr.substring(indexOfStrEnd+1, indexOfStrEnd+2).equals("\"")){//开头有问题
			int index=messageStr.indexOf("{",indexOfStrEnd+1);
			if(index!=-1)
				messageStr = messageStr.substring(messageStr.indexOf("{", indexOfStrEnd+1));//获取可用的子串
		}
		indexOfStrEnd=messageStr.indexOf("}}}");
		if(indexOfStrEnd!=-1){//特殊消息（内容较多的消息）
			if(indexOfStrEnd+2<messageStr.length()){//存在两条消息
				int index=messageStr.indexOf("{",indexOfStrEnd);
				if(index!=-1)
					MessageDecode(messageStr.substring(index));//生成一个子串作为新的一条json
			}
		}
		else{
			indexOfStrEnd=messageStr.indexOf("}}");
			if(indexOfStrEnd==-1)//没有符合条件的json字串
				return;
			if(indexOfStrEnd+2<messageStr.length()){//存在两条消息
				MessageDecode(messageStr.substring(messageStr.indexOf("{",indexOfStrEnd)));//生成一个子串作为新的一条json
			}
		}
		messageReceied = messageHelper.MessageDecode(messageStr);
		if(messageReceied==null)
			return;
		else {
			handle(messageReceied);
		}
	}

	public void handle(Object message) {
		if (message.getClass().equals(Danmu.class)) {// 弹幕
			Connection conn = null;
			Danmu danmu = (Danmu) message;
			String driver = "com.mysql.jdbc.Driver";
        	String url = "jdbc:mysql://5716e40e38f53.gz.cdb.myqcloud.com:10823/panda?useSSL=true";
			String user = "cdb_outerroot";
        	String password = "fmm529529529";

			try {
				Class.forName(driver);
				conn = (Connection) DriverManager.getConnection(url, user, password);
//				if (!conn.isClosed())
//					System.out.println("Succeeded connecting to the Database!");
				String sql = "insert into danmuinfo (roomid, recTime) values(?,?)";
				PreparedStatement preparedStatement = (PreparedStatement) conn.prepareStatement(sql);
				preparedStatement.setInt(1, Integer.parseInt(danmu.mRoomID));
				preparedStatement.setTimestamp(2, new Timestamp(danmu.mTime*1000));
				//preparedStatement.setString(3, danmu.mContent);
				int re = preparedStatement.executeUpdate();
				if (re > 0) {
					System.out.println("Insert Danmu Success");
				}
				// conn.commit();
//				preparedStatement.close();
//				conn.close();
			} catch (Exception e) {
				// TODO: handle exception
				System.out.println("Sorry,can`t find the Driver!");
				e.printStackTrace();
			}finally {
				try {
					if (conn != null) {
						conn.close();
					}
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
			return;
		} else if (message.getClass().equals(Bamboo.class)) {// 竹子
			Connection conn = null;
			Bamboo bamboo = (Bamboo) message;
			String driver = "com.mysql.jdbc.Driver";
			String url = "jdbc:mysql://5716e40e38f53.gz.cdb.myqcloud.com:10823/panda?useSSL=true";
			String user = "cdb_outerroot";
			String password = "fmm529529529";

			try {
				Class.forName(driver);
				conn = (Connection) DriverManager.getConnection(url, user, password);
				String sql = "insert into zhuziinfo (roomid, recTime, zhuzi) values(?,?,?)";
				PreparedStatement preparedStatement = (PreparedStatement) conn.prepareStatement(sql);
				preparedStatement.setInt(1, Integer.parseInt(bamboo.mRoomID));
				preparedStatement.setTimestamp(2, new Timestamp(bamboo.mTime*1000));
				preparedStatement.setInt(3, Integer.parseInt(bamboo.mContent));
				int re = preparedStatement.executeUpdate();
				if (re > 0) {
					System.out.println("Insert Bamboo Success");
				}
				// conn.commit();
				//conn.close();
			}catch (Exception e) {
				// TODO: handle exception
				System.out.println("Sorry,can`t find the Driver!");
				e.printStackTrace();
			}finally {
				try {
					conn.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
			return;
		}else if (message.getClass().equals(Gift.class)) {// 礼物
			Connection conn = null;
			Gift gift = (Gift) message;
			String driver = "com.mysql.jdbc.Driver";
			String url = "jdbc:mysql://5716e40e38f53.gz.cdb.myqcloud.com:10823/panda?useSSL=true";
			String user = "cdb_outerroot";
			String password = "fmm529529529";
			try {
				Class.forName(driver);
				conn = (Connection) DriverManager.getConnection(url, user, password);
//				if (!conn.isClosed())
//					System.out.println("Succeeded connecting to the Database!");
				String sql = "insert into presentinfo (roomid, recTime, presentvalue) values(?,?,?)";
				PreparedStatement preparedStatement = (PreparedStatement) conn.prepareStatement(sql);
				preparedStatement.setInt(1, Integer.parseInt(gift.mRoomID));
				preparedStatement.setTimestamp(2, new Timestamp(gift.mTime*1000));
				preparedStatement.setInt(3, Integer.parseInt(gift.mContentPrice));
				//preparedStatement.setInt(4, 1);
				int re = preparedStatement.executeUpdate();
				if (re > 0) {
					System.out.println("Insert Gift Success");
				}
				// conn.commit();
				//preparedStatement.close();

			}catch (Exception e) {
				// TODO: handle exception
				System.out.println("Sorry,can`t find the Driver!");
				e.printStackTrace();
			}finally {
				try {
					if (conn != null) {
						conn.close();
					}
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
			return;

		}

	}
//	else if (messageReceied.getClass().equals(Visitors.class)) {// 访客数量
//			Visitors visitor = (Visitors) messageReceied;
//
//			String driver = "com.mysql.jdbc.Driver";
//			String url = "jdbc:mysql://127.0.0.1:3306/panda?useSSL=true";
//			String user = "root";
//			String password = "qwerty123456";
//
//			try {
//				Class.forName(driver);
//				Connection conn = (Connection) DriverManager.getConnection(url, user, password);
//				if (!conn.isClosed())
//					System.out.println("Succeeded connecting to the Database!");
//				String sql = "insert into roominfo (roomid, recTime, audienceNum) values(?,?,?)";
//				PreparedStatement preparedStatement = (PreparedStatement) conn.prepareStatement(sql);
//				preparedStatement.setInt(1, Integer.parseInt(visitor.mRoomID));
//				preparedStatement.setTimestamp(2, new Timestamp(visitor.mTime*1000));
//				preparedStatement.setInt(3, Integer.parseInt(visitor.mContent));
//				int re = preparedStatement.executeUpdate();
//				if (re > 0) {
//					System.out.println("Insert Fansnum Success");
//				}
//				// conn.commit();
//				conn.close();
//			} catch (Exception e) {
//				// TODO: handle exception
//				System.out.println("Sorry,can`t find the Driver!");
//				e.printStackTrace();
//			}
//		 mVisitorNum.setText(visitor.mContent);
//		return;
//	}
	public void Close() {
		if (socChannel != null && os != null && is != null && socChannel.isConnected()) {
			try {
				mIsHeartBeatThreadStop = true;// 停止心跳线程
				mIsReceivMsgThreadStop = true;// 停止弹幕消息接收
				os.close();
				is.close();
				socChannel.close();
			} catch (IOException e) {
				// e.printStackTrace();
				System.out.println(e);
			}
		}
	}
}
