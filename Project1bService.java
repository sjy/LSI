import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * Servlet implementation class HelloWorld
 */
@WebServlet("/Project1bService")
public class Project1bService extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private static final String START_MESSAGE = "Hello, User!";
	private static final String END_MESSAGE = "Bye!";
	private static final String COOKIE_NAME = "CS5300PROJ1SESSIONvn76";
	private static final int EXPIRATION_PERIOD = 600000; //10 minutes in milliseconds
	private static final int MAX_STRING_LENGTH = 460; 
	private static final int MAX_ENTRIES = 1000;
	private static final int SCHEDULER_TIMEOUT = 600000; //10 minutes in milliseconds
	private static AtomicInteger sessionID = new AtomicInteger();
	private static ConcurrentHashMap<String, SessionTableValue> sessionTable = new ConcurrentHashMap<String, SessionTableValue>();
	private Timer timer = new Timer();
	private static ArrayList<String> ServerList = new ArrayList<String>();
	
	//OPCODES FOR RPC 
	public static final int SESSIONREAD = 1000;
	public static final int SESSIONWRITE = 1001;
	public static final int MAXPACKETSIZE = 512;
	
	
//    private static CopyOnWriteArrayList<String> ServerList = new CopyOnWriteArrayList();
	/**
	 * Inner class for Session Table 
	 */
	
	public static String getSessionTableEntry(String sessionID) {
		SessionTableValue value = sessionTable.get(sessionID);
		if(value == null) {
			return null;
		}
		String result = value.getVersion()+"_"+value.getMessage()+"_"+value.getDate();
		return result;
	}
	private class SessionTableValue {
		//Class to hold data in Session Table
		int version;
		String message;
		String date;
	
		SessionTableValue(int version, String message, String date) {
			this.version = version;
			this.message = message;
			this.date = date;
		}

		public int getVersion() {
			return version;
		}

		public void setVersion(int version) {
			this.version = version;
		}

		public String getMessage() {
			return message;
		}

		public void setMessage(String message) {
			this.message = message;
		}

		public String getDate() {
			return date;
		}

		public void setDate(String date) {
			this.date = date;
		}
		
	}
	
	/**
	 * Inner class RunTimer to schedule the thread for session table cleaning. 
	 */
	private class RunTimer extends TimerTask {
		   public void run() {
			   try {
				runSessionTableCleaner();
			} catch (ParseException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			  
			   //Schedule a timer to call session Table cleaner function
			   timer.schedule(new RunTimer(), SCHEDULER_TIMEOUT);
		   }
	    }
	
	/**
	 * Default constructor.
	 * @throws SocketException 
	 */
	public Project1bService() throws SocketException {
		//Initialize and schedule timer for cleaner thread
        RunTimer runTimer = new RunTimer();
        timer.schedule(runTimer, SCHEDULER_TIMEOUT);
        new Thread(new ServerRPC());
	}
	
	/**
	 * Given an array of cookies return the cookie if its name matches cookieName
	 * and cookieName is present in session table and return its value (String)
	 * @param cookies
	 * @param cookieName
	 * @return Cookie
	 */
	private Cookie getCookie(Cookie[] cookies, String cookieName){
		if (cookies != null) {
			for (int i = 0; i < cookies.length; i++) {
				Cookie cookie = cookies[i];
				String value = cookie.getValue();
				String SID = value.split("_")[0]+"_"+value.split("_")[1]+"_"+value.split("_")[2];
				//verify if cookie is valid
				//check if there is a cookie returned and also if an entry exists in the sessionTable 
				if (cookieName.equals(cookie.getName()) && sessionTable.containsKey(SID))
					return (cookie);
			}
		}
		return null;
	}
	
	/**
	 * Given a HttpServletRequest, return the sesionID of the cookie if present.
	 * Else return null
	 * 
	 * @param request
	 * @return String SID : Session ID
	 * @throws UnknownHostException 
	 * @throws SocketException 
	 */
	private String getSessionID(HttpServletRequest request) throws UnknownHostException, SocketException {
		Cookie cookie = getCookie(request.getCookies(), COOKIE_NAME);
		String SID;
		String sessionValue;
		if (cookie != null) {
			String value = cookie.getValue();
			if(value!= null) {
				String[] values = value.split("_");
				SID = values[0]+"_"+values[1]+"_"+values[2];
				return SID;
			}
		}
		return null;
	}
	
	private String getSessionValue(HttpServletRequest request) throws UnknownHostException, SocketException{
		//check if SID is in other session tables AND if any of those session tables have the most recent value
		Cookie cookie = getCookie(request.getCookies(), COOKIE_NAME);
		String value =  cookie.getValue();
		String sessionTableValue = null;
		
		if(value!= null) {
			String[] values = value.split("_");
			String SID = values[0]+"_"+values[1]+"_"+values[2];
			int version = Integer.valueOf(values[3]);
			//check if SID is in local session table AND if the local table has the most recent value
			if(sessionTable.containsKey(SID) && version == sessionTable.get(SID).getVersion()){
			    sessionTableValue = getSessionTableEntry(SID);
			}
			else{
			    InetAddress[] destAddrs = {InetAddress.getByName(values[4]),InetAddress.getByName(values[6])};
				int[] destPorts = {Integer.valueOf(values[5]), Integer.valueOf(values[7])};
				sessionTableValue = RPCSessionTableLookup(SID, version, destAddrs, destPorts);
			}
		}
		return sessionTableValue;
		
	}
	
	/**
	 * Given a cookie sesionID, refer session table and return if cookie 
	 * has expired (stale) or not.
	 * 
	 * @param sessionID
	 * @return true or false
	 * @throws ParseException 
	 */
	private boolean isCookieStale(String sessionTableValue) throws ParseException {
		//Check if SID exists locally, else do RPC to checkRP if present on RPC servers - done in getSessionValue()
		//Check version compatibility - done in getSessionValue()
		//TODO 3. Check Timestamp
		if(sessionTableValue != null) {
			//compare date in cookie and date stored in sessionTable
			String values[] = sessionTableValue.split("_");
			Date oldDate = new SimpleDateFormat("MMMMM dd, yyyy hh:mm:ss a ", Locale.US).parse(values[2]);
			Timestamp oldTS = new Timestamp(oldDate.getTime());
			Timestamp currentTS = new Timestamp(new Date().getTime());
			long diffTS = currentTS.getTime() - oldTS.getTime();
			
			if (diffTS >= EXPIRATION_PERIOD) { //Cookie is stale
				return true;
			} else { //Cookie is not stale
				return false;
			}
		}
		//Cookie is stale by default
		return true;
	}
	
	/**
	 * Given HttpServletRequest, HttpServletResponse and a String message, create a new 
	 * cookie if one is not present or has become stale or update the cookie value
	 * if cookie is found.
	 * 
	 * @param request
	 * @param response
	 * @param startMessage
	 * @throws SocketException 
	 * @throws UnknownHostException 
	 */
	private void updateCookie(HttpServletRequest request, HttpServletResponse response, String startMessage) throws UnknownHostException, SocketException {
		Date date = new Date();
		SimpleDateFormat ft = new SimpleDateFormat("MMMMM dd, yyyy hh:mm:ss a ", Locale.US);
		String time = ft.format(date);
		Cookie clientCookie = getCookie(request.getCookies(), COOKIE_NAME);	
		String SID;
		SessionTableValue value;
		String IPP_primary;
		String IPP_backup;
		
		if (clientCookie == null) { //Create a new cookie for a new session if one does not exist 
			IPP_primary = request.getLocalAddr() + "_" +request.getLocalPort();
			int versionNo = 1;
			int session = sessionID.incrementAndGet(); 
			SID = ""+session+"_"+IPP_primary;
			//Location metadata will be appropriately added when needed
			value = new SessionTableValue(versionNo, startMessage, time);
			IPP_backup = RPCSessionTableUpdate(SID, value);
			sessionTable.put(SID, value);
			String cookieValue = SID + "_" + versionNo +"_"+ IPP_primary +"_"+ IPP_backup + "location";
			System.out.println("update cookie: "+cookieValue);
			clientCookie = new Cookie(COOKIE_NAME, cookieValue);
		} else { // Update the existing cookie with new values
			SID = getSessionID(request);
			
			String values[] = clientCookie.getValue().split("_");
			int versionNo = Integer.valueOf(values[3])+1;
			value = new SessionTableValue(versionNo, startMessage, time);
			
			//Location metadata will be appropriately added when needed
			IPP_primary = values[4];
			IPP_backup = values[5];
			if(ServerList.contains(IPP_primary) == false)
			    ServerList.add(IPP_primary);
			if(ServerList.contains(IPP_backup) == false)
			    ServerList.add(IPP_backup);
			IPP_backup = RPCSessionTableUpdate(SID, value);
			String cookieValue = SID + "_" + versionNo +"_"+ IPP_primary +"_"+ IPP_backup + "location";
			System.out.println("update cookie: "+cookieValue);
			clientCookie.setValue(cookieValue);
			sessionTable.replace(SID, value);
		}
		clientCookie.setMaxAge((int) (EXPIRATION_PERIOD/1000)); //in seconds
		response.addCookie(clientCookie);
	}

	private String RPCSessionTableUpdate(String sID, SessionTableValue value) {
		return sID;
		// TODO Auto-generated method stub
		//Sends RPC to all servers in ServerList
		//Returns the IPP of response from the first server
	}
	
	private String RPCSessionTableLookup(String SID, int version, InetAddress[] destAddrs, int[] destPorts) throws SocketException {
		// TODO Auto-generated method stub
		//Looks up for a valid entry in IPP_primary and IPP_backup
		//It gets back values in response
		
		//Call RPCClient
		String arguments = "SESSIONREAD"+"_"+SID+"_"+version; //TODO:opcode
		ClientRPC client = new ClientRPC(arguments, destAddrs, destPorts); //TODO:opcode
		String result = client.run();
		return result;
	}

	/**
	 * Generate HTML markup
	 * @param startMessage
	 * @param hostname
	 * @param port
	 * @return markup
	 */
	protected String generateMarkup(String startMessage, String hostname, int port) {
		//Time Expiry is calculated at current + 10 minutes. 
		Date serverDate = new Date();
		Calendar cal = Calendar.getInstance();
		cal.setTime(serverDate);
		cal.add(Calendar.MILLISECOND, EXPIRATION_PERIOD);
		serverDate = cal.getTime(); //Date on server might be in different time zone
		Date date = new Date(serverDate.getTime() + TimeZone.getTimeZone("EST").getRawOffset());
		
		SimpleDateFormat ft = new SimpleDateFormat("MMMMM dd, yyyy hh:mm:ss a ", Locale.US);
		String time = ft.format(date);
		
		String markup = "<h2>"
				+ startMessage
				+ "</h2>"
				+ "<form action=\"\" method=\"post\"> "
				+ "<input style=\"display:inline;\"type=\"submit\" name=\"Action\" value=\"Replace\"/> <input type=\"text\" name=\"replace_string\"/></br><br/>"
				+ "<input type=\"submit\" name=\"Action\" value=\"Refresh\" /><br/><br/>"
				+ "<input type=\"submit\" name=\"Action\" value=\"Logout\" /><br/><br/></form>"
				+ "Session on " + hostname
				+ ":" + port + "<br/><br/>" + "Expires "
				+ time + " EST";
		
		return markup;
	}
	
	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse
	 *      response)
	 */
	protected void doGet(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
		String startMessage = START_MESSAGE;
		String SID = getSessionID(request);
		
		if(SID != null) {
			startMessage = sessionTable.get(SID).getMessage();
		}
		
		//Give the user a cookie on first access to our service.
		updateCookie(request, response, startMessage);
		
		out.println(generateMarkup(startMessage, InetAddress.getLocalHost().getHostAddress(), request.getServerPort()));
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse
	 *      response)
	 */
	protected void doPost(HttpServletRequest request,
			HttpServletResponse response) throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		String SID = getSessionID(request);
		System.out.println("do post - SID:"+SID);
		String startMessage = START_MESSAGE;
		response.setContentType("text/html");
		String action = request.getParameter("Action");
 
		if (action.equals("Logout")) {
			//remove session table entry and print bye message
			sessionTable.remove(SID);
			out.println("<h2>"+END_MESSAGE+"</h2>");	
		} else {
			//Extract replace string and set to startMessage
			if (action.equals("Replace")) {
				startMessage = request.getParameter("replace_string");
			}
			
			String sessionTableValue = getSessionValue(request);
			//Handle valid and stale(expired) cookies 
			try {
				if(!isCookieStale(sessionTableValue)) {
					//Refresh the page with the same text retained only if cookie is valid
					if(action.equals("Refresh")) {
						startMessage = sessionTable.get(SID).getMessage();
					}
				} else { //Cookie is stale so remove entry from session table
					sessionTable.remove(SID);
				}
			} catch (ParseException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			//Validate startMessage
			//Ensure the entered message is <= MAX_STRING_LENGTH(512 bytes)
			if(startMessage.length() > MAX_STRING_LENGTH){
				startMessage = startMessage.substring(0, MAX_STRING_LENGTH);
			}
			
			//Update cookie for all further actions except Logout
			updateCookie(request, response, startMessage);
			
			out.println(generateMarkup(startMessage, InetAddress.getLocalHost().getHostAddress(), request.getLocalPort()));
		}
	}	
		
	/**
	 * Cleans the session table based on the size of the table.
	 * @throws ParseException 
	 */
	private void runSessionTableCleaner() throws ParseException{
		//Clean the session table only if its size has exceeded beyond MAX_ENTRIES
		if(sessionTable.size() >= MAX_ENTRIES){
			Iterator<String> it = sessionTable.keySet().iterator();
			Timestamp currentTS = new Timestamp(new Date().getTime());
			while (it.hasNext()) {
				//Remove all stale(expired) cookie entries from Session Table
				String key = it.next();
		    	Date oldDate = new SimpleDateFormat("MMMMM dd, yyyy hh:mm:ss a ", Locale.US).parse(sessionTable.get(key).getDate());
		    	Timestamp oldTS = new Timestamp(oldDate.getTime());
		    	long diffTS = currentTS.getTime() - oldTS.getTime();
		    	if (diffTS >= EXPIRATION_PERIOD){
					sessionTable.remove(key);
				}	    	
		    }
		}
	}
}
