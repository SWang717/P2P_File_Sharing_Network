import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Scanner;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class javap2p {
    /*
     * volatile variables that allow the different thread access the same value
     * of variable
     */
    static final int TIMEOUT = 120000;
    static final int HeartBeatRefresh = 100000;
    static volatile String command;
    static volatile int queryPort;
    static volatile int filePort;
    static volatile int UDPPort;
    static HashSet<String> fileToShare = new HashSet<String>();
    static HashSet<String> fileObtained;
    static HashSet<String> queryIds;
    static String ip;
    static InetAddress peerIP;
    static InetAddress fileDesIP;
    static int fileDesPort;
    static String fileName;
    static String query;
    static int queryID;
    static Vector<Peer> neighbors;
    static ConcurrentLinkedQueue<String> Receivedqueries;
    static ConcurrentLinkedQueue<String> Receivedresponses;
    static ConcurrentHashMap<String, Peer> traceBack;
    static volatile boolean fileFlag;

    /* adding sleep to allow handshake */

    public static void main(String[] args)
            throws UnknownHostException, IOException {

        /* Firstly get the ip address for the host that we are working on */
        String[] ipInfo = InetAddress.getByName("eecslab-12.case.edu")
                .toString().split("/", 2);
        ip = ipInfo[1];
        System.out.println("The IP address of the current machine is: " + ip);

        Receivedqueries = new ConcurrentLinkedQueue<String>();
        Receivedresponses = new ConcurrentLinkedQueue<String>();

        traceBack = new ConcurrentHashMap<String, Peer>();
        javap2p.command = " ";

        File file = new File("/home/sxw909/p2p/config_peer.txt");
        Scanner reader = new Scanner(file);

        neighbors = new Vector<Peer>();

        /* reading the port numbers from the file for configuration */
        while (reader.hasNext()) {
            String temp_line = reader.next();
            if (temp_line.charAt(0) == 'U') {
                temp_line = reader.next();
                UDPPort = Integer.parseInt(temp_line);
            } else if (temp_line.charAt(0) == 'Q') {
                temp_line = reader.next();
                queryPort = Integer.parseInt(temp_line);
            } else if (temp_line.charAt(0) == 'F') {
                temp_line = reader.next();
                filePort = Integer.parseInt(temp_line);
            }

        }
        //Closing the reader for the configuration file
        reader.close();

        javap2p.queryID = 0;
        javap2p.query = javap2p.queryID + ip;

        //Just to initialize the required file component
        javap2p.fileFlag = false;
        javap2p.fileDesIP = InetAddress.getByName("eecslab-12.case.edu");

        /* Initialize the set that has the all the files that can be shared */

        File sharingfile = new File("/home/sxw909/p2p/config_sharing.txt");
        Scanner sharingReader = new Scanner(sharingfile);
        synchronized (javap2p.fileToShare) {
            while (sharingReader.hasNext()) {
                String temp_line = sharingReader.nextLine();
                javap2p.fileToShare.add(temp_line);

            }
        }
        System.out.println("Files inside of  this peer");
        for (String s : fileToShare) {
            System.out.println(s);
        }

        sharingReader.close();
        ConcurrentLinkedQueue<connection> connections = new ConcurrentLinkedQueue<connection>();

        // I use UDP instead of the reading the neighbors configuration file for the extra credits
        DatagramSocket UDP = new DatagramSocket(javap2p.UDPPort);
        UDPSocket UDPsocket = new UDPSocket(UDP);
        UDPsocket.start();

        UDPReceiverSocket UDPReceiver = new UDPReceiverSocket(UDP, connections);
        UDPReceiver.start();

        //a main controller that establish TCP connection when have two PO from other peers
        mainController mC = new mainController(connections, traceBack);
        mC.start();

        //opening the welcome sockets for query and file requests
        fileQuery fSocket = new fileQuery(javap2p.filePort);
        TCPQuery qSocket = new TCPQuery(javap2p.queryPort, traceBack);
        fileReceiver fRec = new fileReceiver();

        fSocket.start();
        qSocket.start();
        fRec.start();

        /* A buffer that reading the input from the user */
        BufferedReader userInput = new BufferedReader(
                new InputStreamReader(System.in));
        while (true) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e1) {
                System.out.println("The keyword not responding");
                e1.printStackTrace();
            }
            System.out.println("Please enter your command:");
            String in = userInput.readLine();
            if (in.contains("Get")) {
                String[] commands = in.split(" ", 2);
                javap2p.command = commands[0];
                javap2p.fileName = commands[1].substring(1,
                        commands[1].length() - 1);
                queryID++;
                query = queryID + ip;
                String queryRequest = "Q:(" + query + ");(" + javap2p.fileName
                        + ")";
                System.out.println("Query message: " + queryRequest);
                if (neighbors.size() == 0) {
                    System.out
                            .println("Can't get with no existing connection!");
                } else {
                    for (Peer n : neighbors) {
                        try {
                            synchronized (n.outStream) {
                                n.outStream.writeBytes(queryRequest + "\n");
                                System.out.println(
                                        "Query sent to " + n.outStr + ".");
                            }

                        } catch (IOException e) {
                            e.printStackTrace();
                            System.out.println(
                                    "Failed sending the query request");
                        }
                    }
                }
            } else if (in.contains("Leave")) {

                System.out.println("Close all existing connections");
                for (Peer n : neighbors) {

                    n.close();

                }

            } else if (in.contains("Exit")) {
                System.out.println("Exit!!");
                System.exit(0);
            } else if (in.contains("Connect")) {
                String[] commands = in.split(" ", 3);
                javap2p.command = commands[0];
                String[] info = commands[1].split("\\.");
                byte[] buf = new byte[4];
                for (int a = 0; a < 4; a++) {
                    buf[a] = (byte) (Integer.parseInt(info[a]) & 0xff);
                }
                javap2p.peerIP = InetAddress.getByAddress(buf);
                buf = new byte[4];

            }

        }

    }

    /*
     * A class connection that stores the connection would be established
     */
    public static class connection {
        InetAddress ip;
        int port;

        public connection(InetAddress address, int po) {
            this.ip = address;
            this.port = po;
        }
    }

    /*
     * the Class peer that maintain a TCP connection
     */
    public static class Peer {
        InetAddress addr;
        int port;
        String outStr;
        volatile Integer timer;
        volatile Boolean disconnect;

        Socket socket;
        BufferedReader inStream;
        DataOutputStream outStream;

        Peer(Socket newSocket) {
            this.socket = newSocket;
            this.addr = this.socket.getInetAddress();
            this.port = this.socket.getPort();
            this.outStr = this.addr.getHostAddress() + ":" + this.port;
            try {
                this.inStream = new BufferedReader(
                        new InputStreamReader(this.socket.getInputStream()));
                this.outStream = new DataOutputStream(
                        this.socket.getOutputStream());
            } catch (IOException e) {
                e.printStackTrace();
            }
            ;
            this.timer = 0;
            this.disconnect = false;
        }

        synchronized void close() {
            this.disconnect = true;
            try {
                this.inStream.close();
                this.outStream.close();
                this.socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /*
     * A class main controller that open TCP connection that
     */
    public static class mainController extends Thread {
        ConcurrentLinkedQueue<connection> connections;
        ConcurrentHashMap<String, Peer> traceBack;

        public mainController(ConcurrentLinkedQueue<connection> connections,
                ConcurrentHashMap<String, Peer> traceback) throws IOException {
            this.connections = connections;
            this.traceBack = traceback;
        }

        /**
         *
         */
        @Override
        public void run() {
            while (!javap2p.command.equals("Exit")) {
                synchronized (this.connections) {
                    if (this.connections.size() == 2) {
                        /* creating sockets that could */
                        connection co = this.connections.poll();
                        connection co2 = this.connections.poll();

                        try {
                            synchronized (javap2p.neighbors) {
                                Socket connectionSocket1 = new Socket(co.ip,
                                        co.port);
                                Peer neighbor = new Peer(connectionSocket1);
                                javap2p.neighbors.add(neighbor);
                                Thread query = new QuerySocket(neighbor,
                                        this.traceBack);

                                System.out
                                        .println("Requested a TCP connection");
                                query.start();

                                Socket connectionSocket2 = new Socket(co2.ip,
                                        co2.port);
                                Peer neighbor2 = new Peer(connectionSocket2);
                                javap2p.neighbors.add(neighbor2);
                                Thread query2 = new QuerySocket(neighbor2,
                                        this.traceBack);
                                System.out
                                        .println("Requested a TCP connection");
                                query2.start();
                                if (javap2p.command.equals("Exit")) {
                                    System.out.println("Disconnect");
                                    neighbor2.close();
                                    neighbor.close();
                                }
                            }

                        } catch (IOException e1) {
                            System.out.println("Not able to build connect");
                            e1.printStackTrace();
                        }

                    }

                }

            }
            System.exit(0);
        }

    }

    public static class fileQuery extends Thread {
        private ServerSocket welcomeSocket;

        public fileQuery(int portNumber) throws IOException {
            this.welcomeSocket = new ServerSocket(portNumber);
        }

        @Override
        public void run() {
            while (javap2p.command.charAt(0) != 'E') {

                try {
                    Socket requestedSocket = this.welcomeSocket.accept();
                    Peer fileSocket = new Peer(requestedSocket);
                    Thread file = new FileSocket(fileSocket);
                    System.out.println("Accepted a file TCP connection");

                    file.start();
                } catch (IOException e) {
                    System.out.println("Unable to build file server conenct");
                    e.printStackTrace();
                }
            }
        }

    }

    /*
     * A tcp welcoming socket class
     */
    public static class TCPQuery extends Thread {
        LinkedList<QuerySocket> TCPconnections;
        private ServerSocket welcomeSocket;
        ConcurrentHashMap<String, Peer> traceBack;

        public TCPQuery(int portNumber,
                ConcurrentHashMap<String, Peer> traceback) throws IOException {
            this.welcomeSocket = new ServerSocket(portNumber);
            this.traceBack = traceback;
        }

        @Override
        public void run() {

            while (javap2p.command.charAt(0) != 'E') {
                try {
                    Socket requestedSocket = this.welcomeSocket.accept();
                    Peer neighbor = new Peer(requestedSocket);
                    javap2p.neighbors.add(neighbor);
                    Thread query = new QuerySocket(neighbor, this.traceBack);
                    System.out.println("Accepted a TCP connection");
                    query.start();
                } catch (IOException e) {
                    System.out.print(
                            "couldn't establish the connection for query");
                    e.printStackTrace();
                }

            }

        }

        public LinkedList<QuerySocket> getSockets() {
            return this.TCPconnections;
        }
    }

    /*
     * a query tcp connection class that operating queues
     */
    public static class QuerySocket extends Thread {

        ConcurrentHashMap<String, Peer> traceBack;
        Peer TCPConnection;

        public QuerySocket(Peer p, ConcurrentHashMap<String, Peer> traceback) {
            this.TCPConnection = p;
            this.traceBack = traceback;

        }

        @Override
        public void run() {
            TCPConnectionHeartBeat heartBeat = new TCPConnectionHeartBeat(
                    this.TCPConnection);
            heartBeat.start();

            System.out.println("A TCP connection has been established!");
            while (!this.TCPConnection.disconnect) {
                if (javap2p.command.equals("Leave")) {
                    this.TCPConnection.close();
                }

                String in = " ";
                try {
                    in = this.TCPConnection.inStream.readLine();
                } catch (IOException e1) {
                    System.out
                            .println("Unable to read from the TCP connection");
                    e1.printStackTrace();
                }
                synchronized (this.TCPConnection.timer) {

                    this.TCPConnection.timer = 0;
                }
                if (in.charAt(0) == 'Q') {
                    System.out.println("Reveived a query");

                    String[] query = in.split(";", 2);
                    String fileName = query[1].substring(1,
                            query[1].length() - 1);
                    String queryID = query[0].substring(3,
                            query[0].length() - 1);
                    synchronized (this.traceBack) {
                        this.traceBack.put(queryID, this.TCPConnection);
                    }
                    synchronized (javap2p.Receivedqueries) {
                        if (!javap2p.Receivedqueries.contains(queryID)) {
                            javap2p.Receivedqueries.add(queryID);
                            synchronized (javap2p.fileToShare) {
                                Boolean contain = false;
                                for (String s : fileToShare) {
                                    if (s.equals(fileName)) {

                                        contain = true;
                                    }
                                }

                                if (contain) {
                                    System.out.print("The file can be shared!");
                                    String res = "R:(" + queryID + ");(" + ip
                                            + ":" + filePort + ");(" + fileName
                                            + ")";
                                    System.out.println(
                                            "Sent a response back: " + res);
                                    synchronized (this.TCPConnection.outStream) {
                                        try {
                                            this.TCPConnection.outStream
                                                    .writeBytes(res + "\n");

                                        } catch (IOException e) {
                                            System.out.println(
                                                    "Unable to response the request");
                                            e.printStackTrace();
                                        }
                                    }

                                } else {
                                    System.out.println(
                                            "passing the request message to the neibors");
                                    System.out.println(
                                            "The file cannot be found!");

                                    for (Peer p : javap2p.neighbors) {
                                        if (p != this.TCPConnection) {
                                            try {
                                                synchronized (p.outStream) {
                                                    p.outStream.writeBytes(
                                                            in + "\n");
                                                }

                                            } catch (IOException e) {
                                                e.printStackTrace();
                                                System.out.println(
                                                        "Cannot proceed queries flooding");
                                            }
                                        }
                                    }

                                }
                            }
                        } else {
                            //Printing out the message that the requries has been received before
                            System.out.println("Dupulated query found");
                        }
                    }
                } else if (in.charAt(0) == 'R') {

                    System.out.println("Reveived a response message");

                    String[] query = in.split(";", 3);
                    String queryID = query[0].substring(3,
                            query[0].length() - 1);
                    String file = query[2].substring(1, query[2].length() - 1);
                    Boolean notContain = true;

                    //Check if this response has been received before
                    synchronized (javap2p.Receivedresponses) {

                        for (String s : Receivedresponses) {
                            if (s.equals(in)) {

                                notContain = false;
                            }
                        }
                        if (notContain) {
                            javap2p.Receivedresponses.add(in);
                        }
                    }
                    //if the query is for this sercer that has the requested file information
                    if (javap2p.query.equals(queryID)
                            && javap2p.fileName.equals(file) && notContain) {
                        System.out.println("Response that found the file");
                        String[] fileInfo = query[1].split(":", 2);
                        try {
                            javap2p.fileDesIP = InetAddress
                                    .getByName(fileInfo[0].substring(1));
                        } catch (UnknownHostException e) {
                            System.out.print(
                                    "Unable to get the file request ip address");
                            e.printStackTrace();
                        }
                        javap2p.fileDesPort = Integer.parseInt(fileInfo[1]
                                .substring(0, fileInfo[1].length() - 1));
                        String fileName = query[2].substring(1,
                                query[2].length() - 1);
                        System.out.println("Start requesting the file: "
                                + fileName + " from " + javap2p.fileDesIP + " "
                                + javap2p.fileDesPort);
                        //informing the file receiver thread to get the file
                        javap2p.fileFlag = true;

                    } else if (notContain) {

                        //Using the hashmap implemented to determine which socket to send back the response
                        synchronized (this.traceBack) {
                            Peer so = this.traceBack.get(queryID);

                            try {
                                synchronized (so.outStream) {

                                    so.outStream.writeBytes(in + "\n");
                                    System.out.println(
                                            "passing the response message to the neibors");
                                }
                                for (Peer n : neighbors) {
                                    try {
                                        synchronized (n.outStream) {
                                            n.outStream.writeBytes(in + "\n");

                                        }

                                    } catch (IOException e) {
                                        e.printStackTrace();
                                        System.out.println(
                                                "Failed sending the query request");
                                    }
                                }

                            } catch (IOException e) {
                                System.out.println("Unable to upload the file");
                                e.printStackTrace();
                            }

                        }
                    }
                }
            }

        }

    }

    /*
     * Heartbeat class that keep monitoring the active of the tcp connections
     */
    public static class TCPConnectionHeartBeat extends Thread {

        public static final int Refresh_Time = 100;

        Peer TCPConnection;
        int clientTimer;

        public TCPConnectionHeartBeat(Peer c) {
            this.TCPConnection = c;
            this.clientTimer = 0;
        }

        @Override
        public void run() {
            //keep running until the tcp connecti
            while (!this.TCPConnection.disconnect
                    && !javap2p.command.equals("Leave")) {
                if (this.TCPConnection.timer > javap2p.TIMEOUT) {
                    System.out
                            .println("Connection timeout, closing connection.");
                    synchronized (this.TCPConnection.disconnect) {
                        this.TCPConnection.disconnect = true;
                    }
                }

                if (this.clientTimer > javap2p.HeartBeatRefresh) {
                    System.out.println("Make sure the HeartBeat exist");
                    synchronized (this.TCPConnection.outStream) {
                        try {

                            boolean isAlive = true;
                            this.TCPConnection.socket.setKeepAlive(isAlive);
                            if (isAlive) {
                                System.out.println("The conenction is alive");
                                synchronized (this.TCPConnection.timer) {
                                    this.TCPConnection.timer = 0;
                                }
                            } else {
                                System.out.println("The connection has lost");
                                this.TCPConnection.close();
                            }
                        } catch (IOException ioe) {
                            // ioe.printStackTrace();
                            System.out.println("Unable to send the heartbeat");
                            synchronized (this.TCPConnection.disconnect) {
                                this.TCPConnection.disconnect = true;
                            }
                        }
                    }
                    this.clientTimer = 0;
                }
                try {
                    sleep(Refresh_Time);

                } catch (InterruptedException e) {
                    System.out.println("The sleep is interrupted");
                    e.printStackTrace();
                }

                synchronized (this.TCPConnection.timer) {
                    this.TCPConnection.timer += Refresh_Time;
                }
                this.clientTimer += Refresh_Time;
            }
        }
    }

    /*
     * A file socket class that uploading files
     */
    public static class FileSocket extends Thread {
        Peer peer;
        PrintWriter outForServer;
        fileQuery file;
        BufferedReader inForServer;
        InetAddress fileDesIP;

        public FileSocket(Peer p) {
            this.peer = p;

        }

        @Override
        public void run() {

            while (!this.peer.disconnect) {

                String in;
                try {
                    InputStream Input = this.peer.socket.getInputStream();
                    InputStreamReader read = new InputStreamReader(Input);
                    BufferedReader reader = new BufferedReader(read);

                    in = reader.readLine();
                    System.out.println("Received the content");
                    if (in.contains("T:(")) {
                        System.out.println("Received a request: " + in);
                        String[] info = in.split(":", 2);
                        String fileName = info[1].substring(1,
                                info[1].length() - 1);

                        OutputStream output = this.peer.socket
                                .getOutputStream();
                        PrintWriter writer = new PrintWriter(output);

                        FileReader file = new FileReader(
                                "/home/sxw909/p2p/shared/" + fileName);
                        BufferedReader buffreader = new BufferedReader(file);
                        System.out.println(
                                "Start uploading the file: " + fileName);
                        String line = buffreader.readLine();
                        while (line != null) {

                            writer.write(line);
                            line = buffreader.readLine();

                        }
                        System.out
                                .println("Sent the requested file" + fileName);
                        reader.close();

                        this.peer.close();
                        buffreader.close();
                    }

                } catch (IOException e) {
                    System.out.println("Unable to read the request");
                    e.printStackTrace();
                }

            }
        }
    }

    /*
     * A file receiver class that downloading the class
     */
    public static class fileReceiver extends Thread {
        Peer peer;
        String fileRequestM;

        public fileReceiver() {
            this.peer = null;
            this.fileRequestM = null;
        }

        @Override
        public void run() {
            try {
                while (!javap2p.command.equals("Exit")) {
                    if (javap2p.fileFlag) {
                        try {//changing to the file socket and thread building
                            String transferRequest = "T:(" + javap2p.fileName
                                    + ")";
                            Socket fileSocket = new Socket(javap2p.fileDesIP,
                                    javap2p.filePort);
                            Peer filePeer = new Peer(fileSocket);
                            this.peer = filePeer;
                            this.fileRequestM = transferRequest;
                        } catch (IOException e) {
                            System.out.println(
                                    "The connection for file transfer cannot be established");
                            e.printStackTrace();
                        }

                        FileWriter writer = new FileWriter(
                                "/home/sxw909/p2p/obtained/" + javap2p.fileName,
                                true);
                        BufferedWriter buffwriter = new BufferedWriter(writer);
                        System.out.println(
                                "Create file in obtained" + javap2p.fileName);

                        if (javap2p.command == "Leave") {
                            this.peer.close();
                        }

                        if (!this.peer.disconnect) {

                            System.out.println("Start requesting the file");

                            PrintWriter printWriter = new PrintWriter(
                                    this.peer.outStream);

                            printWriter.write(this.fileRequestM);
                            printWriter.flush();
                            System.out.println(
                                    "Send out file requested message : "
                                            + this.fileRequestM);
                            Thread.sleep(1500);

                            InputStream read = this.peer.socket
                                    .getInputStream();
                            System.out.println("Download the file!");

                            InputStreamReader reader = new InputStreamReader(
                                    read);
                            BufferedReader buffreader = new BufferedReader(
                                    reader);
                            String line = buffreader.readLine();
                            while (line != null
                                    && javap2p.command.charAt(0) == 'L') {

                                buffwriter.write(line);
                                line = buffreader.readLine();

                            }
                        }
                        this.peer.close();
                        buffwriter.close();
                    }
                }

            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (SocketException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /*
     * UDP class that sent PI
     */
    public static class UDPSocket extends Thread {

        private DatagramSocket dataSocket;
        DatagramPacket sendPacket;
        LinkedList<connection> connectionList;

        HashSet<String> receivedPI = new HashSet<String>();

        public UDPSocket(DatagramSocket serverSocket) {

            this.dataSocket = serverSocket;

        }

        @Override
        public void run() {

            while (!javap2p.command.equals("Exit")) {

                if (javap2p.command.equals("Connect")) {

                    if (!javap2p.peerIP.toString().equals(javap2p.ip)) {
                        String localIP = javap2p.ip.toString();

                        String PI = "PI:<" + localIP.toString() + ">:<"
                                + javap2p.UDPPort + ">";
                        byte[] buff = PI.getBytes();

                        this.sendPacket = new DatagramPacket(buff, buff.length,
                                javap2p.peerIP, javap2p.UDPPort);
                        try {
                            this.dataSocket.send(this.sendPacket);
                            buff = new byte[1024];
                        } catch (IOException e) {
                            System.out.println("Unable to send the PI");
                            e.printStackTrace();
                        }

                    }

                } else if (javap2p.command.equals("Leave")) {
                    this.dataSocket.close();
                    //listener.close();

                }
            }

        }
    }

    /*
     * UDP receiver that receive the POs to eastablish the TCP connections later
     */
    public static class UDPReceiverSocket extends Thread {

        DatagramSocket dataSocket;
        ConcurrentLinkedQueue<connection> connections;
        LinkedList<String> listOfPO;

        public UDPReceiverSocket(DatagramSocket dSocket,
                ConcurrentLinkedQueue<connection> connectionsMap) {
            this.dataSocket = dSocket;
            this.connections = connectionsMap;
        }

        @Override
        public void run() {

            HashSet<String> receivedPI = new HashSet<String>();
            this.listOfPO = new LinkedList<String>();
            while (!javap2p.command.equals("Exit")) {
                byte[] receiveData = new byte[1024];
                byte[] sendData = new byte[1024];
                DatagramPacket receivePacket = new DatagramPacket(receiveData,
                        receiveData.length);

                try {
                    this.dataSocket.receive(receivePacket);
                } catch (IOException e2) {
                    System.out.println("Unable to receive the PI");
                    e2.printStackTrace();
                }

                String sentence = new String(receivePacket.getData());
                receiveData = new byte[1024];
                if (sentence.charAt(1) == 'I'
                        && !receivedPI.contains(sentence)) {
                    receivedPI.add(sentence);
                    String[] infos = sentence.split(":", 3);
                    InetAddress connectionIPs = null;
                    try {
                        connectionIPs = InetAddress.getByName(
                                infos[1].substring(1, infos[1].length() - 1));
                    } catch (UnknownHostException e2) {
                        System.out.println("Unable to get the ip");
                        e2.printStackTrace();
                    }
                    int connectionPorts = javap2p.UDPPort;
                    System.out.println("Received a PI!");
                    String PO = "PO:<" + javap2p.ip + ">:<" + javap2p.queryPort
                            + ">";
                    System.out.println("Sent a PO: " + PO);
                    sendData = PO.getBytes();
                    DatagramPacket sendPacket = new DatagramPacket(sendData,
                            sendData.length, connectionIPs, connectionPorts);
                    try {
                        this.dataSocket.send(sendPacket);
                    } catch (IOException e1) {
                        System.out.println("Unable to send the PO");
                        e1.printStackTrace();
                    }

                    for (String po : this.listOfPO) {
                        sendData = new byte[1024];
                        String[] info = po.split(":", 3);
                        InetAddress connectionIP = null;
                        try {
                            connectionIP = InetAddress.getByName(
                                    info[1].substring(1, info[1].length() - 1));
                            int connectionPort = javap2p.UDPPort;
                            sendData = sentence.getBytes();

                            sendPacket = new DatagramPacket(sendData,
                                    sendData.length, connectionIP,
                                    connectionPort);

                        } catch (UnknownHostException e) {
                            System.out.println("Unable to obtain the ip");
                            e.printStackTrace();
                        }

                        try {
                            synchronized (this.dataSocket) {
                                this.dataSocket.send(sendPacket);
                                System.out.println(
                                        "Boradcasting to the neighbors");
                            }
                        } catch (IOException e) {
                            System.out.println("Unable to broadcast");
                            e.printStackTrace();
                        }

                    }

                } else if (sentence.charAt(1) == 'O') {
                    synchronized (this.listOfPO) {
                        this.listOfPO.add(sentence);
                        System.out.println("Adding POs");
                    }

                    if (this.connections.size() < 2) {
                        System.out.println("Received a PO!");
                        String[] info = sentence.split(":", 3);

                        InetAddress connectionIP = null;
                        try {
                            connectionIP = InetAddress.getByName(
                                    info[1].substring(1, info[1].length() - 1));
                        } catch (UnknownHostException e) {
                            System.out.println("Unable to find the IP");
                            e.printStackTrace();
                        }
                        int connectionPort = javap2p.queryPort;

                        connection co = new connection(connectionIP,
                                connectionPort);
                        this.connections.add(co);

                    }

                }

            }
        }
    }
}