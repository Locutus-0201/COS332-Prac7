import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

public class VacationResponder {

    private static final String LOG_FILE = "replied_log.txt";

    public static void main(String[] args) {
        Scanner inputScanner = new Scanner(System.in);
        
        System.out.print("Enter Mail Server IP [Press Enter for 192.168.1.58]: ");
        String serverIP = inputScanner.nextLine().trim();
        if (serverIP.isEmpty()) serverIP = "192.168.1.58";
        
        System.out.print("Enter Mailbox Username [e.g., root or locutus]: ");
        String username = inputScanner.nextLine().trim();
        
        System.out.print("Enter Mailbox Password: ");
        String password = inputScanner.nextLine().trim();

        // 1. Load our memory of who we have already replied to
        Set<String> repliedTo = loadRepliedLog();
        List<String> newTargets = new ArrayList<>();

        System.out.println("\n>>> PHASE 1: CONNECTING TO POP3 (PORT 110) <<<");
        try (Socket pop3Socket = new Socket(serverIP, 110);
             BufferedReader in = new BufferedReader(new InputStreamReader(pop3Socket.getInputStream()));
             OutputStream out = pop3Socket.getOutputStream()) {

            // Read the initial welcome message from Dovecot
            System.out.println("S: " + in.readLine());

            // Authentication
            sendPop3Command(out, "USER " + username, in);
            String passResponse = sendPop3Command(out, "PASS " + password, in);
            
            if (passResponse.startsWith("-ERR")) {
                System.out.println("[X] Authentication failed. Terminating.");
                return;
            }

            // Check how many emails are in the mailbox
            String statResponse = sendPop3Command(out, "STAT", in);
            int messageCount = Integer.parseInt(statResponse.split(" ")[1]);
            System.out.println("\nMailbox contains " + messageCount + " messages.");

            // Loop through all messages
            for (int i = 1; i <= messageCount; i++) {
                System.out.println("\n--- Analyzing Message " + i + " ---");
                sendPop3Command(out, "RETR " + i, in);
                
                String line;
                String fromAddress = "";
                String subject = "";
                boolean isMailingList = false;

                // Read the multiline response until we hit the single period "."
                while ((line = in.readLine()) != null) {
                    if (line.equals(".")) {
                        break; // End of email
                    }
                    
                    // Header Extraction
                    String lowerLine = line.toLowerCase();
                    
                    if (lowerLine.startsWith("from:")) {
                        fromAddress = extractEmail(line);
                    } else if (lowerLine.startsWith("subject:")) {
                        subject = line.substring(8).trim();
                    } 
                    // BONUS MARKS: Detect Mailing Lists
                    else if (lowerLine.startsWith("precedence: list") || 
                               lowerLine.startsWith("precedence: bulk") || 
                               lowerLine.startsWith("list-id:")) {
                        isMailingList = true;
                    }
                }

                System.out.println("Subject: " + subject);
                System.out.println("From: " + fromAddress);

                // Validation Logic
                if (!subject.toLowerCase().contains("prac7")) {
                    System.out.println("Action: Ignoring (Subject does not contain 'prac7').");
                } else if (isMailingList) {
                    System.out.println("Action: Ignoring (Identified as a Mailing List).");
                } else if (repliedTo.contains(fromAddress)) {
                    System.out.println("Action: Ignoring (Already replied to this user previously).");
                } else if (!fromAddress.isEmpty()) {
                    System.out.println("Action: Valid target! Queuing vacation response.");
                    newTargets.add(fromAddress);
                }
            }

            sendPop3Command(out, "QUIT", in);

        } catch (Exception e) {
            System.out.println("POP3 Error: " + e.getMessage());
            return;
        }

        // 2. Send the vacation responses via SMTP
        if (newTargets.isEmpty()) {
            System.out.println("\n>>> No valid new targets found. Sleeping. <<<");
            return;
        }

        System.out.println("\n>>> PHASE 2: CONNECTING TO SMTP (PORT 25) <<<");
        try {
            for (String target : newTargets) {
                sendVacationEmail(serverIP, username, target);
                
                // Add to our memory so we don't email them again next time
                repliedTo.add(target);
                saveToRepliedLog(target);
            }
            System.out.println("\n>>> SUCCESS: All vacation responses dispatched! <<<");

        } catch (Exception e) {
            System.out.println("SMTP Error: " + e.getMessage());
        }
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

    private static String sendPop3Command(OutputStream out, String command, BufferedReader in) throws Exception {
        System.out.println("C: " + command);
        out.write((command + "\r\n").getBytes());
        out.flush();
        String response = in.readLine();
        System.out.println("S: " + response);
        return response;
    }

    private static void sendVacationEmail(String serverIP, String myUsername, String targetEmail) throws Exception {
        System.out.println("\n[Sending Auto-Reply to: " + targetEmail + "]");
        Socket socket = new Socket(serverIP, 25);
        OutputStream out = socket.getOutputStream();
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        
        in.readLine(); // Read 220 banner
        
        sendSmtpCommand(out, "HELO Puddle-Jumper", in);
        sendSmtpCommand(out, "MAIL FROM:<" + myUsername + "@localhost>", in);
        sendSmtpCommand(out, "RCPT TO:<" + targetEmail + ">", in);
        sendSmtpCommand(out, "DATA", in);
        
        StringBuilder email = new StringBuilder();
        email.append("Subject: Re: prac7 (Auto-Reply)\r\n");
        email.append("From: Vacation Responder <" + myUsername + "@localhost>\r\n");
        email.append("To: <" + targetEmail + ">\r\n");
        email.append("\r\n");
        email.append("Hello,\r\n\r\n");
        email.append("I am currently on vacation and will not be checking my email. ");
        email.append("I will respond to your message when I return.\r\n\r\n");
        email.append("Best regards,\r\n" + myUsername + "\r\n");
        email.append("\r\n.\r\n");
        
        out.write(email.toString().getBytes());
        out.flush();
        in.readLine(); // Read 250 OK for data
        
        sendSmtpCommand(out, "QUIT", in);
        socket.close();
    }

    private static void sendSmtpCommand(OutputStream out, String command, BufferedReader in) throws Exception {
        out.write((command + "\r\n").getBytes());
        out.flush();
        in.readLine(); 
    }

    // Extracts email from strings like: "Damian Moustakis <damian@example.com>" -> "damian@example.com"
    private static String extractEmail(String fromHeader) {
        if (fromHeader.contains("<") && fromHeader.contains(">")) {
            return fromHeader.substring(fromHeader.indexOf("<") + 1, fromHeader.indexOf(">")).trim();
        }
        return fromHeader.replace("From:", "").trim(); // Fallback
    }

    private static Set<String> loadRepliedLog() {
        Set<String> log = new HashSet<>();
        try {
            File file = new File(LOG_FILE);
            if (file.exists()) {
                Scanner scanner = new Scanner(file);
                while (scanner.hasNextLine()) {
                    log.add(scanner.nextLine().trim());
                }
                scanner.close();
            }
        } catch (Exception e) {
            System.out.println("Log read error: " + e.getMessage());
        }
        return log;
    }

    private static void saveToRepliedLog(String email) {
        try {
            PrintWriter writer = new PrintWriter(new FileWriter(LOG_FILE, true));
            writer.println(email);
            writer.close();
        } catch (Exception e) {
            System.out.println("Log write error: " + e.getMessage());
        }
    }
}