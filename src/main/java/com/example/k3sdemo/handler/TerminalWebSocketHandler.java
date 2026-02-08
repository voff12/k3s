package com.example.k3sdemo.handler;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private final Map<String, ExecWatch> watches = new ConcurrentHashMap<>();
    private final Map<String, KubernetesClient> clients = new ConcurrentHashMap<>();
    private final Map<String, java.io.OutputStream> outputStreams = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.BlockingQueue<Byte>> inputQueues = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        try {
            String query = session.getUri() != null ? session.getUri().getQuery() : null;
            String namespace = getQueryParam(query, "namespace");
            String pod = getQueryParam(query, "pod");
            String container = getQueryParam(query, "container");

            System.out.println("WebSocket connection established - namespace: " + namespace + ", pod: " + pod + ", container: " + container);

            // Validate required parameters
            if (pod == null || pod.isEmpty() || "default".equals(pod)) {
                String errorMsg = "Pod name is required";
                System.err.println("ERROR: " + errorMsg);
                session.close(CloseStatus.BAD_DATA.withReason(errorMsg));
                return;
            }

            if (namespace == null || namespace.isEmpty()) {
                namespace = "default";
            }

            // Use system properties for connection just like Controllers
            String kubeconfig = System.getProperty("kubeconfig");
            String masterUrl = System.getProperty("kubernetes.master");

            if (kubeconfig != null && !kubeconfig.isEmpty()) {
                System.setProperty("kubeconfig", kubeconfig);
            }

            KubernetesClient client = new KubernetesClientBuilder().build();
            clients.put(session.getId(), client);

            // Verify pod exists and get container info if needed
            io.fabric8.kubernetes.api.model.Pod podObj = client.pods().inNamespace(namespace).withName(pod).get();
            if (podObj == null) {
                String errorMsg = "Pod not found: " + pod + " in namespace: " + namespace;
                System.err.println("ERROR: " + errorMsg);
                session.close(CloseStatus.BAD_DATA.withReason(errorMsg));
                return;
            }

            // If container is empty or "default", try to get the first container from the pod
            if (container == null || container.isEmpty() || "default".equals(container)) {
                if (podObj.getSpec().getContainers() != null && !podObj.getSpec().getContainers().isEmpty()) {
                    container = podObj.getSpec().getContainers().get(0).getName();
                    System.out.println("Using first container: " + container);
                } else {
                    String errorMsg = "No containers found in pod: " + pod;
                    System.err.println("ERROR: " + errorMsg);
                    session.close(CloseStatus.BAD_DATA.withReason(errorMsg));
                    return;
                }
            }

            // Create final variables for use in inner classes
            final String finalPod = pod;
            final String finalContainer = container;
            final String finalNamespace = namespace;

            // Create a custom InputStream that reads from a buffer
            // This allows us to write data dynamically from WebSocket messages
            final java.util.concurrent.BlockingQueue<Byte> inputQueue = new java.util.concurrent.LinkedBlockingQueue<>();
            java.io.InputStream inputStream = new java.io.InputStream() {
                @Override
                public int read() throws IOException {
                    try {
                        Byte b = inputQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                        return b != null ? (b & 0xFF) : -1;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }
                
                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int bytesRead = 0;
                    for (int i = 0; i < len && bytesRead < len; i++) {
                        int byteValue = read();
                        if (byteValue == -1) {
                            return bytesRead > 0 ? bytesRead : -1;
                        }
                        b[off + i] = (byte) byteValue;
                        bytesRead++;
                    }
                    return bytesRead;
                }
                
                @Override
                public int available() throws IOException {
                    return inputQueue.size();
                }
            };
            
            // Store the queue so we can write to it when receiving WebSocket messages
            inputQueues.put(session.getId(), inputQueue);

            // Create output stream handlers with better error handling
            final WebSocketSession finalSession = session;
            java.io.OutputStream outputStream = new java.io.OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    // Log only occasionally to avoid spam
                    if (Math.random() < 0.01) { // Log 1% of calls
                        System.out.println("Output stream write(int) called with byte: " + b);
                    }
                    if (finalSession == null || !finalSession.isOpen()) {
                        throw new IOException("WebSocket session is closed");
                    }
                    try {
                        sendMessage(finalSession, new byte[] { (byte) b });
                    } catch (IOException e) {
                        System.err.println("Error sending output: " + e.getMessage());
                        throw e;
                    }
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    // Log occasionally to avoid spam, but always log when receiving data
                    if (len > 0) {
                        // Only log first few calls or when receiving significant data
                        if (Math.random() < 0.1 || len > 50) {
                            System.out.println("Output stream received " + len + " bytes");
                        }
                    }
                    if (finalSession == null || !finalSession.isOpen()) {
                        System.err.println("ERROR: WebSocket session is closed when trying to write output");
                        throw new IOException("WebSocket session is closed");
                    }
                    try {
                        byte[] bytes = new byte[len];
                        System.arraycopy(b, off, bytes, 0, len);
                        // Send raw bytes - preserve ANSI color codes (\033, \x1b, etc.)
                        // xterm.js will automatically render ANSI escape sequences
                        sendMessage(finalSession, bytes);
                    } catch (IOException e) {
                        System.err.println("Error sending output: " + e.getMessage());
                        e.printStackTrace();
                        throw e;
                    }
                }
            };

            java.io.OutputStream errorStream = new java.io.OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    if (finalSession == null || !finalSession.isOpen()) {
                        throw new IOException("WebSocket session is closed");
                    }
                    try {
                        sendMessage(finalSession, new byte[] { (byte) b });
                    } catch (IOException e) {
                        System.err.println("Error sending error: " + e.getMessage());
                        throw e;
                    }
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    if (finalSession == null || !finalSession.isOpen()) {
                        throw new IOException("WebSocket session is closed");
                    }
                    try {
                        byte[] bytes = new byte[len];
                        System.arraycopy(b, off, bytes, 0, len);
                        sendMessage(finalSession, bytes);
                    } catch (IOException e) {
                        System.err.println("Error sending error: " + e.getMessage());
                        throw e;
                    }
                }
            };

            // Create exec listener with better error handling
            // Use a flag to prevent duplicate cleanup
            final java.util.concurrent.atomic.AtomicBoolean cleanupDone = new java.util.concurrent.atomic.AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicBoolean execOpened = new java.util.concurrent.atomic.AtomicBoolean(false);
            
            ExecListener execListener = new ExecListener() {
                @Override
                public void onOpen() {
                    execOpened.set(true);
                    System.out.println("Exec session opened for pod: " + finalPod + ", container: " + finalContainer);
                    System.out.println("ExecListener.onOpen() completed, shell should be ready");
                    
                    // Try to send a command immediately after opening to trigger output
                    // This helps ensure the shell outputs something
                    try {
                        Thread.sleep(300); // Wait a bit for shell to initialize
                        java.util.concurrent.BlockingQueue<Byte> queue = inputQueues.get(session.getId());
                        if (queue != null && session.isOpen()) {
                            // Send a command to trigger shell output
                            byte[] cmdBytes = "pwd\r\n".getBytes(StandardCharsets.UTF_8);
                            for (byte b : cmdBytes) {
                                queue.put(b);
                            }
                            System.out.println("Sent 'pwd' command from onOpen callback");
                        }
                    } catch (Exception e) {
                        System.err.println("Error in onOpen callback: " + e.getMessage());
                        e.printStackTrace();
                    }
                }

                @Override
                public void onFailure(Throwable t, Response failureResponse) {
                    if (cleanupDone.getAndSet(true)) {
                        // Already cleaned up, ignore
                        return;
                    }
                    
                    String errorMsg = "Exec failed: " + (t != null ? t.getMessage() : "Unknown error");
                    System.err.println("ERROR: ExecListener.onFailure - " + errorMsg);
                    if (t != null) {
                        System.err.println("Exception type: " + t.getClass().getName());
                        t.printStackTrace();
                    }
                    if (failureResponse != null) {
                        System.err.println("Failure response code: " + failureResponse.code());
                        System.err.println("Failure response: " + failureResponse.toString());
                    }
                    
                    // Clean up resources
                    cleanupResources(session.getId());
                    
                    // Close WebSocket session if still open
                    try {
                        if (session.isOpen()) {
                            // Send error message to terminal before closing
                            try {
                                // Use Unicode escape sequence for ANSI color codes
                                String errorColorMsg = "\r\n\u001b[31mError: " + errorMsg + "\u001b[0m\r\n";
                                sendMessage(session, errorColorMsg.getBytes(StandardCharsets.UTF_8));
                            } catch (Exception e) {
                                // Ignore send errors
                            }
                            session.close(CloseStatus.SERVER_ERROR.withReason(errorMsg));
                        }
                    } catch (Exception e) {
                        System.err.println("Error closing session in onFailure: " + e.getMessage());
                    }
                }

                @Override
                public void onClose(int code, String reason) {
                    System.out.println("ExecListener.onClose called - code: " + code + ", reason: " + reason + ", execOpened: " + execOpened.get());
                    
                    // If exec was never opened, this is likely a connection failure
                    // If exec was opened and then closed, it's a normal termination
                    if (!execOpened.get()) {
                        System.out.println("Exec was never opened, treating as connection failure");
                        if (!cleanupDone.getAndSet(true)) {
                            cleanupResources(session.getId());
                            try {
                                if (session.isOpen()) {
                                    session.close(CloseStatus.SERVER_ERROR.withReason("Exec connection failed: " + reason));
                                }
                            } catch (Exception e) {
                                System.err.println("Error closing session: " + e.getMessage());
                            }
                        }
                        return;
                    }
                    
                    // Exec was opened, so this is a normal termination
                    // Don't immediately close WebSocket - let user decide when to disconnect
                    // Only mark watch as closed, but keep WebSocket open
                    System.out.println("Exec session terminated normally (code: " + code + "), keeping WebSocket open");
                    
                    // Remove watch but don't close it (it's already closed)
                    watches.remove(session.getId());
                    
                    // Don't close WebSocket here - let it stay open for user to see the message
                    // The WebSocket will be closed when user closes the terminal tab
                }
            };

            // Build exec command - try different shell commands
            // Note: Do NOT use readingInput() with PipedInputStream - use watch.getInput() instead
            ExecWatch watch;
            String[] shellCommands = {"/bin/sh", "/bin/bash", "sh", "bash"};
            Exception lastException = null;
            
            for (String shellCmd : shellCommands) {
                try {
                    System.out.println("Attempting to exec with command: " + shellCmd);
                    // Use readingInput with ByteArrayInputStream instead of PipedInputStream
                    if (finalContainer != null && !finalContainer.isEmpty() && !"default".equals(finalContainer)) {
                        watch = client.pods().inNamespace(finalNamespace).withName(finalPod).inContainer(finalContainer)
                            .readingInput(inputStream)
                            .writingOutput(outputStream)
                            .writingError(errorStream)
                            .withTTY()
                            .usingListener(execListener)
                            .exec(shellCmd);
                    } else {
                        watch = client.pods().inNamespace(finalNamespace).withName(finalPod)
                            .readingInput(inputStream)
                            .writingOutput(outputStream)
                            .writingError(errorStream)
                            .withTTY()
                            .usingListener(execListener)
                            .exec(shellCmd);
                    }
                    
                    System.out.println("ExecWatch created successfully");
                    
                    watches.put(session.getId(), watch);
                    System.out.println("Successfully created exec watch with command: " + shellCmd);
                    
                    // Wait a bit for onOpen callback and initial shell output
                    try {
                        Thread.sleep(1000); // Wait 1 second for shell to initialize and output prompt
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    
                    if (execOpened.get()) {
                        System.out.println("ExecListener.onOpen() was called successfully");
                        // Wait a bit more for shell to fully initialize
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        
                        // Try to trigger shell prompt by sending commands
                        // Some shells don't output prompt until they receive input
                        try {
                            java.util.concurrent.BlockingQueue<Byte> queue = inputQueues.get(session.getId());
                            if (queue != null) {
                                // Send multiple newlines and a command to trigger output
                                byte[] newlineBytes = "\r\n".getBytes(StandardCharsets.UTF_8);
                                for (byte b : newlineBytes) {
                                    queue.put(b);
                                }
                                Thread.sleep(200);
                                
                                // Send a simple command to test
                                byte[] cmdBytes = "echo 'Shell is ready'\r\n".getBytes(StandardCharsets.UTF_8);
                                for (byte b : cmdBytes) {
                                    queue.put(b);
                                }
                                System.out.println("Sent test commands to shell");
                            } else {
                                System.err.println("Error: Input queue is null after exec opened");
                            }
                        } catch (Exception e) {
                            System.err.println("Error sending commands to shell: " + e.getMessage());
                            e.printStackTrace();
                        }
                    } else {
                        System.out.println("Warning: ExecListener.onOpen() was not called yet, but continuing...");
                    }
                    
                    return; // Success, exit the method
                } catch (Exception e) {
                    System.err.println("Failed to exec with " + shellCmd + ": " + e.getMessage());
                    lastException = e;
                    // Continue to try next command
                }
            }
            
            // If all commands failed, close the session
            String errorMsg = "Failed to exec into pod. Tried commands: " + java.util.Arrays.toString(shellCommands);
            if (lastException != null) {
                errorMsg += ". Last error: " + lastException.getMessage();
                lastException.printStackTrace();
            }
            System.err.println("ERROR: " + errorMsg);
            session.close(CloseStatus.SERVER_ERROR.withReason(errorMsg));
            
        } catch (Exception e) {
            String errorMsg = "Unexpected error in afterConnectionEstablished: " + e.getMessage();
            System.err.println("ERROR: " + errorMsg);
            System.err.println("Exception class: " + e.getClass().getName());
            e.printStackTrace();
            
            // Log the full stack trace for debugging
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            e.printStackTrace(pw);
            System.err.println("Full stack trace:\n" + sw.toString());
            
            try {
                if (session.isOpen()) {
                    String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    session.close(CloseStatus.SERVER_ERROR.withReason(reason));
                }
            } catch (IOException ioException) {
                System.err.println("Error closing session: " + ioException.getMessage());
                ioException.printStackTrace();
            } catch (Exception closeException) {
                System.err.println("Error during session close: " + closeException.getMessage());
                closeException.printStackTrace();
            }
        }
    }

    private void sendMessage(WebSocketSession session, byte[] bytes) throws IOException {
        if (session != null && session.isOpen()) {
            try {
                // Send as text message - xterm.js expects text messages
                // Convert bytes to string, preserving ANSI color codes
                // UTF-8 encoding preserves ANSI escape sequences (\x1b, \033, etc.)
                String text = new String(bytes, StandardCharsets.UTF_8);
                session.sendMessage(new TextMessage(text));
            } catch (Exception e) {
                // If session is closed or error occurs, log and don't throw
                System.err.println("Error sending message to WebSocket: " + e.getMessage());
                throw new IOException("Failed to send message", e);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // Get the input queue for this session
        java.util.concurrent.BlockingQueue<Byte> inputQueue = inputQueues.get(session.getId());
        if (inputQueue != null) {
            try {
                byte[] data = message.getPayload().getBytes(StandardCharsets.UTF_8);
                System.out.println("Received " + data.length + " bytes from WebSocket, adding to input queue");
                
                // Add all bytes to the queue
                for (byte b : data) {
                    inputQueue.put(b);
                }
                System.out.println("Added " + data.length + " bytes to input queue, queue size: " + inputQueue.size());
            } catch (Exception e) {
                System.err.println("Error writing to exec input queue: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            System.err.println("Warning: Input queue not found for session: " + session.getId());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        cleanupResources(session.getId());
    }

    /**
     * Clean up resources for a session
     */
    private void cleanupResources(String sessionId) {
        System.out.println("Cleaning up resources for session: " + sessionId);
        
        // Close ExecWatch first
        ExecWatch watch = watches.remove(sessionId);
        if (watch != null) {
            try {
                System.out.println("Closing ExecWatch for session: " + sessionId);
                watch.close();
            } catch (Exception e) {
                System.err.println("Error closing ExecWatch for session " + sessionId + ": " + e.getMessage());
            }
        } else {
            System.out.println("No ExecWatch found for session: " + sessionId);
        }
        
        // Close input stream/output buffer
        java.io.OutputStream outputStream = outputStreams.remove(sessionId);
        if (outputStream != null) {
            try {
                System.out.println("Closing input stream for session: " + sessionId);
                outputStream.close();
            } catch (IOException e) {
                // Ignore errors during cleanup - stream may already be closed
                System.out.println("Input stream already closed for session: " + sessionId);
            }
        }
        
        // Remove input queue
        inputQueues.remove(sessionId);
        
        // Close Kubernetes client
        KubernetesClient client = clients.remove(sessionId);
        if (client != null) {
            try {
                System.out.println("Closing KubernetesClient for session: " + sessionId);
                client.close();
            } catch (Exception e) {
                System.err.println("Error closing KubernetesClient for session " + sessionId + ": " + e.getMessage());
            }
        }
        
        System.out.println("Cleanup completed for session: " + sessionId);
    }

    private String getQueryParam(String query, String key) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        try {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2 && kv[0].equals(key)) {
                    return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }
        return null;
    }
}
