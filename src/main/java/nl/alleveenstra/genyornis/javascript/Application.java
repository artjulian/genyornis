package nl.alleveenstra.genyornis.javascript;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Vector;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.alleveenstra.genyornis.ServerContext;

/**
 * This class represents a JavaScript application.
 *
 * @author alle.veenstra@gmail.com
 */
public class Application extends Thread {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    private static final int EVALUATE_TIMEOUT = 100;
    private Context cx;
    MyFactory contextFactory = new MyFactory();
    private Scriptable scope;
    private File javascript;
    private Vector<String> messages = new Vector<String>();

    private long cpuPerSecond = 0;
    private long lastUptime = 0;
    private long lastThreadCpuTime = 0;

    private boolean running = true;
    private ServerContext context;

    public Application(ServerContext context, File javascript) {
        this.context = context;
        this.javascript = javascript;
    }

    /**
     * Run the JavaScript file.
     */
    public void run() {
        cx = contextFactory.enterContext();
        cx.setOptimizationLevel(-1);
        cx.setMaximumInterpreterStackDepth(24);
        scope = cx.initStandardObjects();

        // make the communication channel available in the scope
        java.lang.Object wrappedPipe = Context.javaToJS(context.channelManager(), scope);
        ScriptableObject.putProperty(scope, "pipe", wrappedPipe);

        // make this application available in this scope
        java.lang.Object wrappedApplication = Context.javaToJS(this, scope);
        ScriptableObject.putProperty(scope, "application", wrappedApplication);

        try {
            cx.evaluateReader(scope, new FileReader(javascript), javascript.getName(), 0, null);
            while (running) {
                try {
                    String message = getMessage();
                    cx.evaluateString(scope, message, "<cmd>", 1, null);
                    sleep(EVALUATE_TIMEOUT);
                } catch (Exception e) {
                    // TODO implement some decent logging
                    e.printStackTrace();
                }
            }
        } catch (FileNotFoundException e) {
            // TODO implement some decent logging
            e.printStackTrace();
        } catch (IOException e) {
            // TODO implement some decent logging
            e.printStackTrace();
        } catch (EvaluatorException e) {
            // Lol exit!
        }
    }

    /**
     * Read one message from the queue.
     *
     * @return a message
     * @throws InterruptedException
     */
    public synchronized String getMessage() throws InterruptedException {
        notify();
        while (messages.size() == 0) {
            wait();
        }
        String message = (String) messages.firstElement();
        messages.removeElement(message);
        return message;
    }

    /**
     * Deliver a message to the JavaScript application by calling a function with the message and sender as parameters.
     *
     * @param callback
     * @param from
     * @param message
     */
    public synchronized void deliver(String callback, String from, String message) {
        String code = callback + "('" + from.replace("'", "\'") + "','" + message.replace("'", "\'") + "')";
        messages.add(code);
        notify();
    }

    public void updateMemoryUsage() {
        Object[] ids = scope.getIds();
        for (Object id : ids) {
            // scope.get(id)
        }
    }

    public void updateCpuUsage() {
        ThreadMXBean mxThread = ManagementFactory.getThreadMXBean();
        RuntimeMXBean mxRuntime = ManagementFactory.getRuntimeMXBean();
        long threadCpuTime = mxThread.getThreadCpuTime(getId());
        long uptime = mxRuntime.getUptime();
        cpuPerSecond = (threadCpuTime - lastThreadCpuTime) / (uptime - lastUptime);
        lastUptime = uptime;
        lastThreadCpuTime = threadCpuTime;
    }

    public long getCpuPerSecond() {
        return cpuPerSecond;
    }

    public void gracefullyQuit() {
        running = false;
        contextFactory.gracefullyQuit();
    }
}