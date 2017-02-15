/*
 * Copyright (C) 2012-2015 Jorrit "Chainfire" Jongma
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.chainfire.libsuperuser;

import android.os.Handler;
import android.os.Looper;

import com.sueztech.screenoffmemo.BuildConfig;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import eu.chainfire.libsuperuser.StreamGobbler.OnLineListener;

/**
 * Class providing functionality to execute commands in a (root) shell
 */
@SuppressWarnings("SameParameterValue")
public class Shell {
    private static final String[] availableTestCommands = new String[]{
            "echo -BOC-",
            "id"
    };

    /**
     * <p>
     * Runs commands using the supplied shell, and returns the output, or null
     * in case of errors.
     * </p>
     * <p>
     * Note that due to compatibility with older Android versions, wantSTDERR is
     * not implemented using redirectErrorStream, but rather appended to the
     * output. STDOUT and STDERR are thus not guaranteed to be in the correct
     * order in the output.
     * </p>
     * <p>
     * Note as well that this code will intentionally crash when run in debug
     * mode from the main thread of the application. You should always execute
     * shell commands from a background thread.
     * </p>
     * <p>
     * When in debug mode, the code will also excessively log the commands
     * passed to and the output returned from the shell.
     * </p>
     * <p>
     * Though this function uses background threads to gobble STDOUT and STDERR
     * so a deadlock does not occur if the shell produces massive output, the
     * output is still stored in a List&lt;String&gt;, and as such doing
     * something like <em>'ls -lR /'</em> will probably have you run out of
     * memory.
     * </p>
     *
     * @param shell       The shell to use for executing the commands
     * @param commands    The commands to execute
     * @param environment List of all environment variables (in 'key=value'
     *                    format) or null for defaults
     * @param wantSTDERR  Return STDERR in the output ?
     * @return Output of the commands, or null in case of an error
     */
    private static List<String> run(String shell, String[] commands, String[] environment,
                                    boolean wantSTDERR) {
        String shellUpper = shell.toUpperCase(Locale.ENGLISH);

        if (BuildConfig.DEBUG && Debug.onMainThread()) {
            // check if we're running in the main thread, and if so, crash if
            // we're in debug mode, to let the developer know attention is
            // needed here.

            Debug.log(ShellOnMainThreadException.EXCEPTION_COMMAND);
            throw new ShellOnMainThreadException();
        }
        Debug.logCommand(String.format("[%s%%] START", shellUpper));

        List<String> res = Collections.synchronizedList(new ArrayList<String>());

        try {
            // Combine passed environment with system environment
            if (environment != null) {
                Map<String, String> newEnvironment = new HashMap<>();
                newEnvironment.putAll(System.getenv());
                int split;
                for (String entry : environment) {
                    if ((split = entry.indexOf("=")) >= 0) {
                        newEnvironment.put(entry.substring(0, split), entry.substring(split + 1));
                    }
                }
                int i = 0;
                environment = new String[newEnvironment.size()];
                for (Map.Entry<String, String> entry : newEnvironment.entrySet()) {
                    environment[i] = entry.getKey() + "=" + entry.getValue();
                    i++;
                }
            }

            // setup our process, retrieve STDIN stream, and STDOUT/STDERR
            // gobblers
            Process process = Runtime.getRuntime().exec(shell, environment);
            DataOutputStream STDIN = new DataOutputStream(process.getOutputStream());
            StreamGobbler STDOUT = new StreamGobbler(shellUpper + "-", process.getInputStream(),
                    res);
            StreamGobbler STDERR = new StreamGobbler(shellUpper + "*", process.getErrorStream(),
                    wantSTDERR ? res : null);

            // start gobbling and write our commands to the shell
            STDOUT.start();
            STDERR.start();
            try {
                for (String write : commands) {
                    Debug.logCommand(String.format("[%s+] %s", shellUpper, write));
                    STDIN.write((write + "\n").getBytes("UTF-8"));
                    STDIN.flush();
                }
                STDIN.write("exit\n".getBytes("UTF-8"));
                STDIN.flush();
            } catch (IOException e) {
                // method most horrid to catch broken pipe, in which case we
                // do nothing. the command is not a shell, the shell closed
                // STDIN, the script already contained the exit command, etc.
                // these cases we want the output instead of returning null
                if (!e.getMessage().contains("EPIPE")) {
                    // other issues we don't know how to handle, leads to
                    // returning null
                    throw e;
                }
            }

            // wait for our process to finish, while we gobble away in the
            // background
            process.waitFor();

            // make sure our threads are done gobbling, our streams are closed,
            // and the process is destroyed - while the latter two shouldn't be
            // needed in theory, and may even produce warnings, in "normal" Java
            // they are required for guaranteed cleanup of resources, so lets be
            // safe and do this on Android as well
            try {
                STDIN.close();
            } catch (IOException e) {
                // might be closed already
            }
            STDOUT.join();
            STDERR.join();
            process.destroy();

            // in case of su, 255 usually indicates access denied
            if (SU.isSU(shell) && (process.exitValue() == 255)) {
                res = null;
            }
        } catch (IOException | InterruptedException e) {
            // shell probably not found
            res = null;
        }

        Debug.logCommand(String.format("[%s%%] END", shell.toUpperCase(Locale.ENGLISH)));
        return res;
    }

    /**
     * See if the shell is alive, and if so, check the UID
     *
     * @param ret          Standard output from running availableTestCommands
     * @param checkForRoot true if we are expecting this shell to be running as
     *                     root
     * @return true on success, false on error
     */
    private static boolean parseAvailableResult(List<String> ret, boolean checkForRoot) {
        if (ret == null)
            return false;

        // this is only one of many ways this can be done
        boolean echo_seen = false;

        for (String line : ret) {
            if (line.contains("uid=")) {
                // id command is working, let's see if we are actually root
                return !checkForRoot || line.contains("uid=0");
            } else if (line.contains("-BOC-")) {
                // if we end up here, at least the su command starts some kind
                // of shell, let's hope it has root privileges - no way to know without
                // additional native binaries
                echo_seen = true;
            }
        }

        return echo_seen;
    }

    private interface OnResult {
        // for any onCommandResult callback
        int WATCHDOG_EXIT = -1;
        int SHELL_DIED = -2;

        // for Interactive.open() callbacks only
        int SHELL_EXEC_FAILED = -3;
        int SHELL_WRONG_UID = -4;
        int SHELL_RUNNING = 0;
    }

    /**
     * Command result callback, notifies the recipient of the completion of a
     * command block, including the (last) exit code, and the full output
     */
    @SuppressWarnings("UnusedParameters")
    interface OnCommandResultListener extends OnResult {
        /**
         * <p>
         * Command result callback
         * </p>
         * <p>
         * Depending on how and on which thread the shell was created, this
         * callback may be executed on one of the gobbler threads. In that case,
         * it is important the callback returns as quickly as possible, as
         * delays in this callback may pause the native process or even result
         * in a deadlock
         * </p>
         * <p>
         * See {@link Shell.Interactive} for threading details
         * </p>
         *
         * @param commandCode Value previously supplied to addCommand
         * @param exitCode    Exit code of the last command in the block
         * @param output      All output generated by the command block
         */
        void onCommandResult(int commandCode, int exitCode, List<String> output);
    }

    /**
     * Command per line callback for parsing the output line by line without
     * buffering It also notifies the recipient of the completion of a command
     * block, including the (last) exit code.
     */
    interface OnCommandLineListener extends OnResult, OnLineListener {
        /**
         * <p>
         * Command result callback
         * </p>
         * <p>
         * Depending on how and on which thread the shell was created, this
         * callback may be executed on one of the gobbler threads. In that case,
         * it is important the callback returns as quickly as possible, as
         * delays in this callback may pause the native process or even result
         * in a deadlock
         * </p>
         * <p>
         * See {@link Shell.Interactive} for threading details
         * </p>
         *
         * @param commandCode Value previously supplied to addCommand
         * @param exitCode    Exit code of the last command in the block
         */
        void onCommandResult(int commandCode, int exitCode);
    }

    /**
     * This class provides utility functions to easily execute commands using SU
     * (root shell), as well as detecting whether or not root is available, and
     * if so which version.
     */
    @SuppressWarnings("UnusedReturnValue")
    public static class SU {

        /**
         * Runs commands as root (if available) and return output
         *
         * @param commands The commands to run
         * @return Output of the commands, or null if root isn't available or in
         * case of an error
         */
        static List<String> run(String[] commands) {
            return Shell.run("su", commands, null, false);
        }

        /**
         * Detects whether or not superuser access is available, by checking the
         * output of the "id" command if available, checking if a shell runs at
         * all otherwise
         *
         * @return True if superuser access available
         */
        public static boolean available() {
            // this is only one of many ways this can be done

            List<String> ret = run(Shell.availableTestCommands);
            return Shell.parseAvailableResult(ret, true);
        }

        /**
         * Attempts to deduce if the shell command refers to a su shell
         *
         * @param shell Shell command to run
         * @return Shell command appears to be su
         */
        static boolean isSU(String shell) {
            // Strip parameters
            int pos = shell.indexOf(' ');
            if (pos >= 0) {
                shell = shell.substring(0, pos);
            }

            // Strip path
            pos = shell.lastIndexOf('/');
            if (pos >= 0) {
                shell = shell.substring(pos + 1);
            }

            return shell.equals("su");
        }

    }

    /**
     * Internal class to store command block properties
     */
    private static class Command {
        private static int commandCounter = 0;

        private final String[] commands;
        private final int code;
        private final OnCommandResultListener onCommandResultListener;
        private final OnCommandLineListener onCommandLineListener;
        private final String marker;

        Command(String[] commands, int code,
                OnCommandResultListener onCommandResultListener,
                OnCommandLineListener onCommandLineListener) {
            this.commands = commands;
            this.code = code;
            this.onCommandResultListener = onCommandResultListener;
            this.onCommandLineListener = onCommandLineListener;
            this.marker = UUID.randomUUID().toString() + String.format("-%08x", ++commandCounter);
        }
    }

    /**
     * Builder class for {@link Shell.Interactive}
     */
    private static class Builder {
        private final List<Command> commands = new LinkedList<>();
        private final Map<String, String> environment = new HashMap<>();
        private final Handler handler = null;
        private final String shell = "sh";
        private final boolean wantSTDERR = false;
        private final OnLineListener onSTDOUTLineListener = null;
        private final OnLineListener onSTDERRLineListener = null;
        private final int watchdogTimeout = 0;

        /**
         * Construct a {@link Shell.Interactive} instance, and start the shell
         *
         * @return Interactive shell
         */
        public Interactive open() {
            return new Interactive(this, null);
        }

        /**
         * Construct a {@link Shell.Interactive} instance, try to start the
         * shell, and call onCommandResultListener to report success or failure
         *
         * @param onCommandResultListener Callback to return shell open status
         * @return Interactive shell
         */
        public Interactive open(OnCommandResultListener onCommandResultListener) {
            return new Interactive(this, onCommandResultListener);
        }
    }

    /**
     * <p>
     * An interactive shell - initially created with {@link Shell.Builder} -
     * that executes blocks of commands you supply in the background, optionally
     * calling callbacks as each block completes.
     * </p>
     * <p>
     * STDERR output can be supplied as well, but due to compatibility with
     * older Android versions, wantSTDERR is not implemented using
     * redirectErrorStream, but rather appended to the output. STDOUT and STDERR
     * are thus not guaranteed to be in the correct order in the output.
     * </p>
     * <p>
     * Note as well that the close() and waitForIdle() methods will
     * intentionally crash when run in debug mode from the main thread of the
     * application. Any blocking call should be run from a background thread.
     * </p>
     * <p>
     * When in debug mode, the code will also excessively log the commands
     * passed to and the output returned from the shell.
     * </p>
     * <p>
     * Though this function uses background threads to gobble STDOUT and STDERR
     * so a deadlock does not occur if the shell produces massive output, the
     * output is still stored in a List&lt;String&gt;, and as such doing
     * something like <em>'ls -lR /'</em> will probably have you run out of
     * memory when using a {@link Shell.OnCommandResultListener}.
     * </p>
     * <h3>Callbacks, threads and handlers</h3>
     * <p>
     * A Handler will
     * be auto-created if the thread used for instantiation of the object has a
     * Looper.
     * </p>
     * <p>
     * If no Handler was supplied and it was also not auto-created, all
     * callbacks will be called from either the STDOUT or STDERR gobbler
     * threads. These are important threads that should be blocked as little as
     * possible, as blocking them may in rare cases pause the native process or
     * even create a deadlock.
     * </p>
     * <p>
     * The main thread must certainly have a Looper, thus if you call
     * {@link Shell.Builder#open()} from the main thread, a handler will (by
     * default) be auto-created, and all the callbacks will be called on the
     * main thread. While this is often convenient and easy to code with, you
     * should be aware that if your callbacks are 'expensive' to execute, this
     * may negatively impact UI performance.
     * </p>
     * <p>
     * Background threads usually do <em>not</em> have a Looper, so calling
     * {@link Shell.Builder#open()} from such a background thread will (by
     * default) result in all the callbacks being executed in one of the gobbler
     * threads. You will have to make sure the code you execute in these
     * callbacks is thread-safe.
     * </p>
     */
    @SuppressWarnings("UnusedReturnValue")
    private static class Interactive {
        private final Handler handler;
        private final String shell;
        private final boolean wantSTDERR;
        private final List<Command> commands;
        private final Map<String, String> environment;
        private final OnLineListener onSTDOUTLineListener;
        private final OnLineListener onSTDERRLineListener;
        private final Object idleSync = new Object();
        private final Object callbackSync = new Object();
        private int watchdogTimeout;
        private Process process = null;
        private DataOutputStream STDIN = null;
        private StreamGobbler STDOUT = null;
        private StreamGobbler STDERR = null;
        private ScheduledThreadPoolExecutor watchdog = null;
        private volatile boolean idle = true; // read/write only synchronized
        private volatile boolean closed = true;
        private volatile int callbacks = 0;
        private volatile int watchdogCount;
        private volatile int lastExitCode = 0;
        private volatile String lastMarkerSTDOUT = null;
        private volatile String lastMarkerSTDERR = null;
        private volatile Command command = null;
        private volatile List<String> buffer = null;

        /**
         * The only way to create an instance: Shell.Builder::open()
         *
         * @param builder Builder class to take values from
         */
        private Interactive(final Builder builder,
                            final OnCommandResultListener onCommandResultListener) {
            shell = builder.shell;
            wantSTDERR = builder.wantSTDERR;
            commands = builder.commands;
            environment = builder.environment;
            onSTDOUTLineListener = builder.onSTDOUTLineListener;
            onSTDERRLineListener = builder.onSTDERRLineListener;
            watchdogTimeout = builder.watchdogTimeout;

            // If a looper is available, we offload the callbacks from the
            // gobbling threads
            // to whichever thread created us. Would normally do this in open(),
            // but then we could not declare handler as final
            if (Looper.myLooper() != null) {
                handler = new Handler();
            } else {
                handler = builder.handler;
            }

            if (onCommandResultListener != null) {
                // Allow up to 60 seconds for SuperSU/Superuser dialog, then enable
                // the user-specified timeout for all subsequent operations
                watchdogTimeout = 60;
                commands.add(0, new Command(Shell.availableTestCommands, 0, new OnCommandResultListener() {
                    public void onCommandResult(int commandCode, int exitCode, List<String> output) {
                        if ((exitCode == OnCommandResultListener.SHELL_RUNNING) &&
                                !Shell.parseAvailableResult(output, Shell.SU.isSU(shell))) {
                            // shell is up, but it's brain-damaged
                            exitCode = OnCommandResultListener.SHELL_WRONG_UID;
                        }
                        watchdogTimeout = builder.watchdogTimeout;
                        onCommandResultListener.onCommandResult(0, exitCode, output);
                    }
                }, null));
            }

            if (!open() && (onCommandResultListener != null)) {
                onCommandResultListener.onCommandResult(0,
                        OnCommandResultListener.SHELL_EXEC_FAILED, null);
            }
        }

        @Override
        protected void finalize() throws Throwable {
            if (!closed && BuildConfig.DEBUG) {
                // waste of resources
                Debug.log(ShellNotClosedException.EXCEPTION_NOT_CLOSED);
                throw new ShellNotClosedException();
            }
            super.finalize();
        }

        /**
         * Run the next command if any and if ready, signals idle state if no
         * commands left
         */
        private void runNextCommand() {
            runNextCommand(true);
        }

        /**
         * Called from a ScheduledThreadPoolExecutor timer thread every second
         * when there is an outstanding command
         */
        private synchronized void handleWatchdog() {
            final int exitCode;

            if (watchdog == null)
                return;
            if (watchdogTimeout == 0)
                return;

            if (!isRunning()) {
                exitCode = OnCommandResultListener.SHELL_DIED;
                Debug.log(String.format("[%s%%] SHELL_DIED", shell.toUpperCase(Locale.ENGLISH)));
            } else if (watchdogCount++ < watchdogTimeout) {
                return;
            } else {
                exitCode = OnCommandResultListener.WATCHDOG_EXIT;
                Debug.log(String.format("[%s%%] WATCHDOG_EXIT", shell.toUpperCase(Locale.ENGLISH)));
            }

            postCallback(command, exitCode, buffer);

            // prevent multiple callbacks for the same command
            command = null;
            buffer = null;
            idle = true;

            watchdog.shutdown();
            watchdog = null;
            kill();
        }

        /**
         * Start the periodic timer when a command is submitted
         */
        private void startWatchdog() {
            if (watchdogTimeout == 0) {
                return;
            }
            watchdogCount = 0;
            watchdog = new ScheduledThreadPoolExecutor(1);
            watchdog.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    handleWatchdog();
                }
            }, 1, 1, TimeUnit.SECONDS);
        }

        /**
         * Disable the watchdog timer upon command completion
         */
        private void stopWatchdog() {
            if (watchdog != null) {
                watchdog.shutdownNow();
                watchdog = null;
            }
        }

        /**
         * Run the next command if any and if ready
         *
         * @param notifyIdle signals idle state if no commands left ?
         */
        private void runNextCommand(boolean notifyIdle) {
            // must always be called from a synchronized method

            boolean running = isRunning();
            if (!running)
                idle = true;

            if (running && idle && (commands.size() > 0)) {
                Command command = commands.get(0);
                commands.remove(0);

                buffer = null;
                lastExitCode = 0;
                lastMarkerSTDOUT = null;
                lastMarkerSTDERR = null;

                if (command.commands.length > 0) {
                    try {
                        if (command.onCommandResultListener != null) {
                            // no reason to store the output if we don't have an
                            // OnCommandResultListener
                            // user should catch the output with an
                            // OnLineListener in this case
                            buffer = Collections.synchronizedList(new ArrayList<String>());
                        }

                        idle = false;
                        this.command = command;
                        startWatchdog();
                        for (String write : command.commands) {
                            Debug.logCommand(String.format("[%s+] %s",
                                    shell.toUpperCase(Locale.ENGLISH), write));
                            STDIN.write((write + "\n").getBytes("UTF-8"));
                        }
                        STDIN.write(("echo " + command.marker + " $?\n").getBytes("UTF-8"));
                        STDIN.write(("echo " + command.marker + " >&2\n").getBytes("UTF-8"));
                        STDIN.flush();
                    } catch (IOException e) {
                        // STDIN might have closed
                    }
                } else {
                    runNextCommand(false);
                }
            } else if (!running) {
                // our shell died for unknown reasons - abort all submissions
                while (commands.size() > 0) {
                    postCallback(commands.remove(0), OnCommandResultListener.SHELL_DIED, null);
                }
            }

            if (idle && notifyIdle) {
                synchronized (idleSync) {
                    idleSync.notifyAll();
                }
            }
        }

        /**
         * Processes a STDOUT/STDERR line containing an end/exitCode marker
         */
        private synchronized void processMarker() {
            if (command.marker.equals(lastMarkerSTDOUT)
                    && (command.marker.equals(lastMarkerSTDERR))) {
                postCallback(command, lastExitCode, buffer);
                stopWatchdog();
                command = null;
                buffer = null;
                idle = true;
                runNextCommand();
            }
        }

        /**
         * Process a normal STDOUT/STDERR line
         *
         * @param line     Line to process
         * @param listener Callback to call or null
         */
        private synchronized void processLine(String line, OnLineListener listener) {
            if (listener != null) {
                if (handler != null) {
                    final String fLine = line;
                    final OnLineListener fListener = listener;

                    startCallback();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                fListener.onLine(fLine);
                            } finally {
                                endCallback();
                            }
                        }
                    });
                } else {
                    listener.onLine(line);
                }
            }
        }

        /**
         * Add line to internal buffer
         *
         * @param line Line to add
         */
        private synchronized void addBuffer(String line) {
            if (buffer != null) {
                buffer.add(line);
            }
        }

        /**
         * Increase callback counter
         */
        private void startCallback() {
            synchronized (callbackSync) {
                callbacks++;
            }
        }

        /**
         * Schedule a callback to run on the appropriate thread
         */
        private void postCallback(final Command fCommand, final int fExitCode,
                                  final List<String> fOutput) {
            if (fCommand.onCommandResultListener == null && fCommand.onCommandLineListener == null) {
                return;
            }
            if (handler == null) {
                if (fCommand.onCommandResultListener != null)
                    fCommand.onCommandResultListener.onCommandResult(fCommand.code, fExitCode,
                            fOutput);
                if (fCommand.onCommandLineListener != null)
                    fCommand.onCommandLineListener.onCommandResult(fCommand.code, fExitCode);
                return;
            }
            startCallback();
            handler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (fCommand.onCommandResultListener != null)
                            fCommand.onCommandResultListener.onCommandResult(fCommand.code,
                                    fExitCode, fOutput);
                        if (fCommand.onCommandLineListener != null)
                            fCommand.onCommandLineListener
                                    .onCommandResult(fCommand.code, fExitCode);
                    } finally {
                        endCallback();
                    }
                }
            });
        }

        /**
         * Decrease callback counter, signals callback complete state when
         * dropped to 0
         */
        private void endCallback() {
            synchronized (callbackSync) {
                callbacks--;
                if (callbacks == 0) {
                    callbackSync.notifyAll();
                }
            }
        }

        /**
         * Internal call that launches the shell, starts gobbling, and starts
         * executing commands. See {@link Shell.Interactive}
         *
         * @return Opened successfully ?
         */
        private synchronized boolean open() {
            Debug.log(String.format("[%s%%] START", shell.toUpperCase(Locale.ENGLISH)));

            try {
                // setup our process, retrieve STDIN stream, and STDOUT/STDERR
                // gobblers
                if (environment.size() == 0) {
                    process = Runtime.getRuntime().exec(shell);
                } else {
                    Map<String, String> newEnvironment = new HashMap<>();
                    newEnvironment.putAll(System.getenv());
                    newEnvironment.putAll(environment);
                    int i = 0;
                    String[] env = new String[newEnvironment.size()];
                    for (Map.Entry<String, String> entry : newEnvironment.entrySet()) {
                        env[i] = entry.getKey() + "=" + entry.getValue();
                        i++;
                    }
                    process = Runtime.getRuntime().exec(shell, env);
                }

                STDIN = new DataOutputStream(process.getOutputStream());
                STDOUT = new StreamGobbler(shell.toUpperCase(Locale.ENGLISH) + "-",
                        process.getInputStream(), new OnLineListener() {
                    @Override
                    public void onLine(String line) {
                        synchronized (Interactive.this) {
                            if (command == null) {
                                return;
                            }

                            String contentPart = line;
                            String markerPart = null;

                            int markerIndex = line.indexOf(command.marker);
                            if (markerIndex == 0) {
                                contentPart = null;
                                markerPart = line;
                            } else if (markerIndex > 0) {
                                contentPart = line.substring(0, markerIndex);
                                markerPart = line.substring(markerIndex);
                            }

                            if (contentPart != null) {
                                addBuffer(contentPart);
                                processLine(contentPart, onSTDOUTLineListener);
                                processLine(contentPart, command.onCommandLineListener);
                            }

                            if (markerPart != null) {
                                try {
                                    lastExitCode = Integer.valueOf(
                                            markerPart.substring(command.marker.length() + 1), 10);
                                } catch (Exception e) {
                                    // this really shouldn't happen
                                    e.printStackTrace();
                                }
                                lastMarkerSTDOUT = command.marker;
                                processMarker();
                            }
                        }
                    }
                });
                STDERR = new StreamGobbler(shell.toUpperCase(Locale.ENGLISH) + "*",
                        process.getErrorStream(), new OnLineListener() {
                    @Override
                    public void onLine(String line) {
                        synchronized (Interactive.this) {
                            if (command == null) {
                                return;
                            }

                            String contentPart = line;

                            int markerIndex = line.indexOf(command.marker);
                            if (markerIndex == 0) {
                                contentPart = null;
                            } else if (markerIndex > 0) {
                                contentPart = line.substring(0, markerIndex);
                            }

                            if (contentPart != null) {
                                if (wantSTDERR)
                                    addBuffer(contentPart);
                                processLine(contentPart, onSTDERRLineListener);
                            }

                            if (markerIndex >= 0) {
                                lastMarkerSTDERR = command.marker;
                                processMarker();
                            }
                        }
                    }
                });

                // start gobbling and write our commands to the shell
                STDOUT.start();
                STDERR.start();

                closed = false;

                runNextCommand();

                return true;
            } catch (IOException e) {
                // shell probably not found
                return false;
            }
        }

        /**
         * Try to clean up as much as possible from a shell that's gotten itself
         * wedged. Hopefully the StreamGobblers will croak on their own when the
         * other side of the pipe is closed.
         */
        synchronized void kill() {
            closed = true;

            try {
                STDIN.close();
            } catch (IOException e) {
                // in case it was closed
            }
            try {
                process.destroy();
            } catch (Exception e) {
                // in case it was already destroyed or can't be
            }

            idle = true;
            synchronized (idleSync) {
                idleSync.notifyAll();
            }
        }

        /**
         * Is our shell still running ?
         *
         * @return Shell running ?
         */
        boolean isRunning() {
            if (process == null) {
                return false;
            }
            try {
                process.exitValue();
                return false;
            } catch (IllegalThreadStateException e) {
                // if this is thrown, we're still running
            }
            return true;
        }

    }
}
