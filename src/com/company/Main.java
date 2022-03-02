package com.company;

import com.fazecast.jSerialComm.SerialPort;

import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.Properties;
import java.util.logging.*;

public class Main {

    // RS485 settings
    static String RS485_PORT; // raspberry Pi zero W
    // General system defaults
    static double MAX_POWER_FROM_MAINS;  // in Watt
    static final int MIN_CHARGING_AMPS = 6;
    static String MASTER_ID;
    static final String MASTER_SIGN = "77"; // not used at this time
    // Web server settings
    static int HTTP_PORT;
    static final String NEW_LINE = "\r\n";
    public static volatile String webResponse = "Tesla Wall charger controller booting...";
    // SMA energy reader or SMA home manager
    static long SMA_SERIAL;
    // Global variables
    static String slaveId;
    static String slaveSign;
    static int maxAmps = 0; // read-in from slave
    static volatile double currentPowerConsumption = 0.0;
    static int currentTWCamps = 8;
    static int currentTWCUsedAmps = -1;
    static long startTime;
    // Logging
    static Logger logger = Logger.getLogger("MyLog");
    static boolean logging = true;

    public static void main(String[] args) throws IOException, InterruptedException {
        // Logging
        // Add logging to a file
        FileHandler fh = new FileHandler("TWC.log");
        logger.addHandler(fh);
        // Override the standard formatter to something on 1 line
        Formatter formatter = new Formatter() {
            @Override
            public String format(LogRecord arg0) {
                StringBuilder b = new StringBuilder();
                b.append(new Date()).append(", ");
                b.append(arg0.getLevel()).append(": ");
                b.append(arg0.getMessage());
                b.append(System.getProperty("line.separator"));
                return b.toString();
            }
        };
        fh.setFormatter(formatter);
        // also set the console logger to the new formatter
        Logger globalLogger = Logger.getLogger("");
        Handler[] handlers = globalLogger.getHandlers();
        for (Handler handler : handlers) {
            handler.setFormatter(formatter);
        }
        // Config file read-in
        File configFile = new File("config.txt");
        if (!configFile.exists()) {
            if (logging) logger.info("Config file does not exist, creating");
            boolean result = configFile.createNewFile();
            if (result) {
                FileWriter fWriter = new FileWriter(configFile);
                fWriter.write("# config file" + NEW_LINE +
                        "#" + NEW_LINE +
                        "# port specifies the http port where the webpage is" + NEW_LINE +
                        "http_port = 8085" + NEW_LINE +
                        "#" + NEW_LINE +
                        "# max_power_from_mains is the maximum power in Watt that you want drawn from the mains" + NEW_LINE +
                        "max_power_from_mains = 10000.0" + NEW_LINE +
                        "#" + NEW_LINE +
                        "# master_id is the 2 byte hex code the master will use" + NEW_LINE +
                        "master_id = 7777" + NEW_LINE +
                        "#" + NEW_LINE +
                        "# rs485_port is the specific string to open the RS485 port" + NEW_LINE +
                        "# windows mostly uses a com port like COM3" + NEW_LINE +
                        "# raspberry pi with a RS485 usb dongle uses something like ttyUSB0" + NEW_LINE +
                        "rs485_port = ttyUSB0" + NEW_LINE +
                        "#" + NEW_LINE +
                        "# sma_serial is the serial nr of the SMA energy meter or SMA home manager" + NEW_LINE +
                        "sma_serial = 3004908651" + NEW_LINE);
                fWriter.close();
            } else {
                if (logging) logger.warning("Could not create config.txt file");
            }
        } else {
            if (logging) logger.info("Existing config.txt file found, reading...");
        }
        FileReader fReader = new FileReader(configFile);
        Properties props = new Properties();
        props.load(fReader);
        fReader.close();
        HTTP_PORT = Integer.parseInt(props.getProperty("http_port", "8085"));
        MAX_POWER_FROM_MAINS = Double.parseDouble(props.getProperty("max_power_from_mains", "10000.0"));
        MASTER_ID = props.getProperty("master_id", "7777");
        RS485_PORT = props.getProperty("rs485_port", "ttyUSB0");
        SMA_SERIAL = Long.parseLong(props.getProperty("sma_serial", "3004908651"));
        // Master id logging
        if (logging) logger.info("This Master is set at Id " + MASTER_ID);
        // start SMA interrogation on separate thread
        SmaThread smaT = new SmaThread("sma");
        Thread newThread = new Thread(smaT);
        newThread.start();
        if (logging) logger.info("SMA power meter started on Thread");
        // start simple website on separate thread
        WebServer webServer = new WebServer("webserver");
        Thread webServerThread = new Thread(webServer);
        webServerThread.start();
        if (logging) logger.info("Web server started on Thread");
        // Open RS485 port to Tesla Wall Charger
        SerialPort comPort = SerialPort.getCommPort(RS485_PORT);
        if (logging) logger.info("Opening RS485 port " + comPort.getSystemPortName());
        comPort.setComPortParameters(9600, 8, 1, SerialPort.NO_PARITY, true);
        comPort.setRs485ModeParameters(true, false, 5, 5);
        comPort.openPort();
        if (logging) logger.info("Port " + RS485_PORT + " opened");
        // Get first block
        String block;
        do {
            block = getNextBlock(comPort);
        } while (deEscapeBlock(block).startsWith("FDE2"));  // we're looking for a slave linkready block
        slaveId = extractSlaveId(deEscapeBlock(block));
        slaveSign = extractSlaveSign(block);
        maxAmps = extractMaxAmps(deEscapeBlock(block));
        if (logging) {
            logger.info("Slave linkready block received " + block);
            logger.info("SlaveId " + slaveId + ", SlaveSign " + slaveSign + ", max amps " + maxAmps);
        }
        String firstHeartbeat = prepareBlock(assembleMasterHeartbeat(MASTER_ID, slaveId, 9, currentTWCamps));
        if (logging) logger.info("Sending heartbeat block " + firstHeartbeat);
        sendBlock(comPort, firstHeartbeat);
        startTime = System.nanoTime();
        while (true) {
            block = getNextBlock(comPort);
            displayBlockProperties(block);
            respondToBlock(comPort, block);
            updateWebServer();
        }
    }

    public static void displayBlockProperties(String block) {
        String strippedBlock = deEscapeBlock(block.substring(2, 34));
        if (strippedBlock.startsWith("FDE0")) {
            String dataBlock = strippedBlock.substring(12, strippedBlock.length() - 2);
            String byte1 = dataBlock.substring(0, 2);
            switch (byte1) {
                case "00":
                    logger.info("Charger ready");
                    break;
                case "01":
                    logger.info("Car plugged in, charging");
                    break;
                case "02":
                    logger.info("Error status");
                    break;
                case "03":
                    logger.info("Car plugged in, not charging");
                    break;
                case "04":
                    logger.info("Car plugged in, ready to charge");
                    break;
                case "05":
                    logger.info("Busy");
                    break;
                case "06":
                    logger.info("Slave acknowledges reception of command code 6");
                    break;
                case "07":
                    logger.info("Slave acknowledges reception of command code 7");
                    break;
                case "08":
                    logger.info("Car plugged in, starting to charge");
                    break;
                case "09":
                    logger.info("Slave acknowledges reception of command code 9");
                    break;
                default:
                    logger.info("Unknown TWC status " + block + ", " + strippedBlock + ", " + dataBlock);
            }
            String byte23 = dataBlock.substring(2, 6);
            double decodeSetAmps = decodeAmps(byte23);
            String byte45 = dataBlock.substring(6, 10);
            double decodeIsAmps = decodeAmps(byte45);
            currentTWCUsedAmps = (int) decodeIsAmps;
            logger.info("TWC reports charging current " + decodeSetAmps + " A set, " + decodeIsAmps + " A used");
        } else if (strippedBlock.startsWith("FDE2")) {
            logger.info("Received slave linkReady block, ignoring");
        }
    }

    public static void respondToBlock(SerialPort sp, String block) throws InterruptedException {
        Thread.sleep(2000);
        int waitingTime = (int) ((System.nanoTime() - startTime) / 1e9);
        int oldAmps = currentTWCamps;
        if (waitingTime > 45) {  // only allow changing amps every 45 seconds
            startTime = System.nanoTime(); // reset the timecounter
            double differenceInAmps;// 1.44e-3 = 1/(400*sqrt(3))
            if ((currentTWCUsedAmps == -1) || (Math.abs(currentTWCamps - currentTWCUsedAmps) < 2)) {
                differenceInAmps = (MAX_POWER_FROM_MAINS - currentPowerConsumption) * 1.44e-3;
            } else {
                differenceInAmps = (MAX_POWER_FROM_MAINS - currentTWCamps * 400 * Math.sqrt(3.0)) * 1.44e-3;
            }
            currentTWCamps = currentTWCamps + (int) Math.round(differenceInAmps);
            if (currentTWCamps < MIN_CHARGING_AMPS) currentTWCamps = 0;
            if (currentTWCamps > maxAmps) currentTWCamps = maxAmps;
            if (logging) logger.info("Charging current change from " + oldAmps + " A to " + currentTWCamps + " A");
        } else {
            if (logging)
                logger.info("Charging current kept at " + currentTWCamps + " A, wait time " + waitingTime + " sec");
        }
        String blockToSend = prepareBlock(assembleMasterHeartbeat(MASTER_ID, slaveId, 9, currentTWCamps));
        sendBlock(sp, blockToSend);
        if (logging) logger.info("Sending block " + blockToSend);
    }

    public static String getNextBlock(SerialPort sp) throws InterruptedException {
        String block = "";
        while (true) { // only exit the loop when a valid block is found, else log it and retry
            do {
                while (sp.bytesAvailable() == -1) { // wait for something to come in
                    Thread.sleep(250);
                }
                Thread.sleep(250); // let data come in
                int bytesInBuffer = sp.bytesAvailable();
                byte[] readBuffer = new byte[bytesInBuffer];
                sp.readBytes(readBuffer, readBuffer.length);
                block = block + byteArrayToHexString(readBuffer);
            }
            while (!block.endsWith("C0FC"));
            block = cleanUpBlock(block);
            if (isValidBlock(block)) {
                if (logging) logger.info("Block received " + block);
                return block;
            } else {
                if (logging) logger.warning("Block checksum failed " + block);
            }
        }
    }

    public static void sendBlock(SerialPort sp, String block) throws InterruptedException {
        sp.writeBytes(hexStringToByteArray(block), block.length() / 2);
        Thread.sleep(250);
    }

    public static String cleanUpBlock(String rawblock) {
        int posC0 = (rawblock.substring(0, rawblock.length() - 4)).lastIndexOf("C0");  // take out the COFC at the end and then look for the last occurrence of C0, that will be the start of a block
        return rawblock.substring(posC0);
    }

    public static boolean isValidBlock(String block) {
        if ((!block.startsWith("C0")) || (!block.endsWith("C0FC"))) return false;
        block = block.substring(2, block.length() - 4); // remove first byte and last 2 bytes
        block = deEscapeBlock(block);
        String extractedChecksum = block.substring(block.length() - 2);
        block = block.substring(0, block.length() - 2);
        return (extractedChecksum.equals(calculateChecksum(block)));
    }

    public static String deEscapeBlock(String block) {
        block = block.replaceAll("DBDC", "C0");
        block = block.replaceAll("DBDD", "DB");
        return block;
    }

    public static String escapeBlock(String block) {
        block = block.replaceAll("DB", "DBDD"); // this order is important, DB before C0
        block = block.replaceAll("C0", "DBDC");
        return block;
    }

    public static String calculateChecksum(String block) {
        int checksum = 0;
        // do not include first byte in checksum calculation, so teller = 1
        for (int teller = 1; teller < block.length() / 2; teller++) {
            checksum += Integer.parseInt(block.substring(teller * 2, (teller + 1) * 2), 16);
        }
        String checksumString = Integer.toHexString(checksum & 0xFF).toUpperCase();  // only the least significant byte matters
        if (checksumString.length() == 1) {
            checksumString = "0" + checksumString;
        }
        return checksumString;
    }

    public static String assembleMasterHeartbeat(String masterId, String slaveId, int commandCode, int setAmps) {
        if ((commandCode < 0) || (commandCode > 9)) commandCode = 9;
        StringBuilder sb = new StringBuilder("FBE0");
        sb.append(masterId);
        sb.append(slaveId);
        sb.append("0");
        sb.append(commandCode); // tell slave to limit power to setAmps in following 2 bytes
        String amps;
        if ((setAmps <= maxAmps) && (setAmps > 0)) {
            amps = Integer.toHexString(setAmps * 100);
            while (amps.length() < 4) {
                amps = "0" + amps;
            }
        } else {
            amps = "0258"; // set 6A
        }
        sb.append(amps.toUpperCase());
        sb.append("00"); // byte 4
        sb.append("00000000"); // byte 5-6-7-8 are empty
        return sb.toString();
    }

    public static double decodeAmps(String amps) {
        int ampsI = Integer.parseInt(amps, 16);
        double ampD = ((double) ampsI) / 100.0;
        return ampD;
    }

    public static String extractSlaveId(String test) {
        int position = test.indexOf("C0FDE2");
        return test.substring(position + 6, position + 10);
    }

    public static String extractSlaveSign(String test) {
        int position = test.indexOf("C0FDE2");
        return test.substring(position + 10, position + 12);
    }

    public static int extractMaxAmps(String test) {
        int position = test.indexOf("1F40");
        if (position != -1) return 80;
        position = test.indexOf("0C80");
        if (position != -1) return 32;
        else return 0;
    }

    public static String prepareBlock(String message) {
        // calculates byte checksum and add it to end
        // escapes the message
        // adds C0 to front of message
        // adds C0 to end of message
        // return String ready to transmit
        return "C0" + escapeBlock(message + calculateChecksum(message)) + "C0";
    }

    public static void updateWebServer() {
        StringBuilder sb = new StringBuilder();
        sb.append("Tesla Wall charger controller status").append(NEW_LINE);
        sb.append("------------------------------------").append(NEW_LINE).append(NEW_LINE);
        sb.append("Master Id: ").append(MASTER_ID).append(NEW_LINE);
        sb.append("Slave  Id: ").append(slaveId).append(", charger is capable of ").append(maxAmps).append("A").append(NEW_LINE).append(NEW_LINE);
        sb.append("Maximum power draw from mains set at: ").append(String.format("%5.0f", MAX_POWER_FROM_MAINS)).append("W").append(NEW_LINE);
        sb.append("Current power consumption from mains: ").append(String.format("%5.0f", currentPowerConsumption)).append("W (").append(String.format("%4.1f", currentPowerConsumption / 692.0)).append("A)").append(NEW_LINE);
        sb.append("Current power setting on TWC        : ").append(String.format("%5.0f", currentTWCamps * 693.0)).append("W (").append(String.format("%4.1f", (double) currentTWCamps)).append("A)").append(NEW_LINE);
        sb.append("Reported power consumption by TWC   : ").append(String.format("%5.0f", currentTWCUsedAmps * 693.0)).append("W (").append(String.format("%4.1f", (double) currentTWCUsedAmps)).append("A)").append(NEW_LINE).append(NEW_LINE);
        //sb.append("Current amps from mains   : ").append(String.format("%4.1f", currentPowerConsumption / 692.0)).append("A").append(NEW_LINE);
        //sb.append("Current amp setting on TWC: ").append(String.format("%4.1f", (double) currentTWCamps)).append("A").append(NEW_LINE).append(NEW_LINE);
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
        sb.append("Time stamp: ").append(dtf.format(LocalDateTime.now()));
        webResponse = sb.toString();
    }

    static class SmaThread implements Runnable {

        String threadName;

        SmaThread(String name) {
            threadName = name;
        }

        @Override
        public void run() {
            final String sma_multicastIp = "239.12.255.254";
            final int sma_multicastPort = 9522;
            String myHostIpAddress = getIpAddress();
            while (true) {
                if (logging) logger.info("Opening SMA multicast socket from " + myHostIpAddress);
                try {
                    InetAddress mcastAddr = InetAddress.getByName(sma_multicastIp);
                    InetSocketAddress group = new InetSocketAddress(mcastAddr, sma_multicastPort);
                    NetworkInterface netIf = NetworkInterface.getByName(myHostIpAddress);
                    MulticastSocket mcSocket = new MulticastSocket(sma_multicastPort);
                    mcSocket.joinGroup(group, netIf);
                    byte[] txbuf = hexStringToByteArray("534d4100000402a0ffffffff0000002000000000");  // discovery string to be sent to network, all SMA devices will answer
                    if (logging)
                        logger.info("Sending out SMA specific discovery code " + byteArrayToHexString(txbuf) + " to multicast address " + sma_multicastIp + "/" + sma_multicastPort);
                    DatagramPacket data = new DatagramPacket(txbuf, txbuf.length, mcastAddr, sma_multicastPort);
                    mcSocket.send(data);
                    byte[] buffer = new byte[1024];
                    data = new DatagramPacket(buffer, buffer.length);
                    long watchDog = System.nanoTime();
                    long showCounter = 0;
                    while ((System.nanoTime() - watchDog) < 30e9) { // 30 sec watchdog check
                        mcSocket.receive(data);
                        byte[] slice = Arrays.copyOfRange(buffer, 0, data.getLength());
                        smaResponseData smaR = parseSmaResponse(slice);
                        if ((smaR != null) && (smaR.serial == SMA_SERIAL)) {
                            watchDog = System.nanoTime();
                            showCounter++;
                            currentPowerConsumption = smaR.power3f.doubleValue();
                            if ((logging) && (showCounter % 65 == 0)) {
                                logger.info("SMA power meter reports " + (int) currentPowerConsumption + " Watt consumption from grid");
                            }
                        }
                    }
                    if (logging) logger.warning("SMA: watchDog timer exceeded, reconnecting multicast socket");
                    mcSocket.close();
                } catch (IOException e) {
                    if (logging) logger.warning("SMA: Multicast failed");
                }
            }
        }
    }

    public static smaResponseData parseSmaResponse(byte[] hexData) {

        // Extracting the correct values from the measurement byte[]
        // Up to now (2020) it is 600 or 608 bytes long: 600 for SMA energy meter and 608 for the SMA home manager 2
        // To be as futureproof as possible, we do not use the data byte offsets given by SMA (since they already changed once with a firmware update)
        // For the measurement we search the hexData for a specific marker and then extract from 4 to 8 bytes further the data
        // These markers are stored in the enum internalData
        String hexString = byteArrayToHexString(hexData);
        if (hexString.length() < 1200) return null;
        else {
            smaResponseData smar = new smaResponseData();
            // the serial number is extracted direct from byte 20 to 24, then converted to a unsigned long
            // int is too short and would give negative serial numbers for some devices
            int serial = ByteBuffer.wrap(Arrays.copyOfRange(hexData, 20, 24)).getInt();
            smar.serial = Integer.toUnsignedLong(serial);
            // Power is read relative to a marker position, but for every value there are 2 markers
            // one for the positive power, one for the negative
            // at least one of them is always zero
            // also, the power is stored in 0.1W numbers, so we need to divide by 10 to get the value in Watts
            // to make searching the marker position easier to program, we convert the byte[] to a hexString and look for the markers in this String
            // the extraction is done from the byte[] with the search result from above (divided by 2)
            // for correctness, the result is stored in a BigDecimal
            int power3fp = getValueFromMarker(hexData, internalData.power3fpos);
            int power3fn = getValueFromMarker(hexData, internalData.power3fneg);
            if (power3fp != 0) {
                smar.power3f = BigDecimal.valueOf(power3fp).divide(BigDecimal.TEN);
            } else {
                smar.power3f = BigDecimal.valueOf(-power3fn).divide(BigDecimal.TEN);
            }
            int powerL1p = getValueFromMarker(hexData, internalData.powerL1pos);
            int powerL1n = getValueFromMarker(hexData, internalData.powerL1neg);
            if (powerL1p != 0) {
                smar.powerL1 = BigDecimal.valueOf(powerL1p).divide(BigDecimal.TEN);
            } else {
                smar.powerL1 = BigDecimal.valueOf(-powerL1n).divide(BigDecimal.TEN);
            }
            int powerL2p = getValueFromMarker(hexData, internalData.powerL2pos);
            int powerL2n = getValueFromMarker(hexData, internalData.powerL2neg);
            if (powerL2p != 0) {
                smar.powerL2 = BigDecimal.valueOf(powerL2p).divide(BigDecimal.TEN);
            } else {
                smar.powerL2 = BigDecimal.valueOf(-powerL2n).divide(BigDecimal.TEN);
            }
            int powerL3p = getValueFromMarker(hexData, internalData.powerL3pos);
            int powerL3n = getValueFromMarker(hexData, internalData.powerL3neg);
            if (powerL3p != 0) {
                smar.powerL3 = BigDecimal.valueOf(powerL3p).divide(BigDecimal.TEN);
            } else {
                smar.powerL3 = BigDecimal.valueOf(-powerL3n).divide(BigDecimal.TEN);
            }
            return smar;
        }
    }

    public static int getValueFromMarker(byte[] hexData, internalData marker) {
        String hexDataString = byteArrayToHexString(hexData);
        int markerLocation = hexDataString.indexOf(marker.code);
        if (markerLocation == -1) return 0;
        markerLocation = markerLocation / 2;
        return ByteBuffer.wrap(Arrays.copyOfRange(hexData, markerLocation + marker.offset, markerLocation + marker.offset + marker.length)).getInt();
    }

    public static class smaResponseData {
        long serial;
        BigDecimal power3f = BigDecimal.ZERO;
        BigDecimal powerL1 = BigDecimal.ZERO;
        BigDecimal powerL2 = BigDecimal.ZERO;
        BigDecimal powerL3 = BigDecimal.ZERO;
    }

    enum internalData {
        // These are the 4 byte markers in hex
        power3fpos("00010400", 4, 4),
        power3fneg("00020400", 4, 4),
        powerL1pos("00150400", 4, 4),
        powerL1neg("00160400", 4, 4),
        powerL2pos("00290400", 4, 4),
        powerL2neg("002A0400", 4, 4),
        powerL3pos("003D0400", 4, 4),
        powerL3neg("003E0400", 4, 4);

        final String code;
        final int offset;
        final int length;

        internalData(String code, int offset, int length) {
            this.code = code;
            this.offset = offset;
            this.length = length;
        }
    }

    public static class WebServer implements Runnable {

        String threadName;

        WebServer(String name) {
            threadName = name;
        }

        @Override
        public void run() {
            try {
                ServerSocket socket = new ServerSocket(HTTP_PORT);
                while (true) {
                    Socket connection = socket.accept();
                    try {
                        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                        OutputStream out = new BufferedOutputStream(connection.getOutputStream());
                        PrintStream pout = new PrintStream(out);
                        String request = in.readLine();
                        if (request == null) continue;
                        while (true) {
                            String ignore = in.readLine();
                            if (ignore == null || ignore.length() == 0) break;
                        }
                        if (!request.startsWith("GET ") || !(request.endsWith(" HTTP/1.0") || request.endsWith(" HTTP/1.1"))) {
                            pout.print("HTTP/1.0 400 Bad Request" + NEW_LINE + NEW_LINE);
                        } else {
                            pout.print("HTTP/1.0 200 OK" + NEW_LINE + "Content-Type: text/plain" + NEW_LINE + "Date: " + new Date() + NEW_LINE + "Content-length: " + webResponse.length() + NEW_LINE + NEW_LINE + webResponse);
                        }
                        pout.close();
                    } catch (Throwable tri) {
                        if (logging) logger.warning("Http error handling request: " + tri);
                    }
                }
            } catch (Throwable tr) {
                if (logging) logger.warning("Could not start http server: " + tr);
            }
        }
    }

    public static String getIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
            return "";
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
    }

    public static String byteArrayToHexString(byte[] bytes) {
        if (bytes == null) return "";
        final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static byte[] hexStringToByteArray(String hex) {
        hex = hex.length() % 2 != 0 ? "0" + hex : hex;
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int index = i * 2;
            int v = Integer.parseInt(hex.substring(index, index + 2), 16);
            b[i] = (byte) v;
        }
        return b;
    }
}
