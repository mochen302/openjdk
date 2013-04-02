/*
 * Copyright (c) 2009, 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */


package sun.util.logging;

import java.lang.ref.WeakReference;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import sun.misc.JavaLangAccess;
import sun.misc.SharedSecrets;

/**
 * Platform logger provides an API for the JRE components to log
 * messages.  This enables the runtime components to eliminate the
 * static dependency of the logging facility and also defers the
 * java.util.logging initialization until it is enabled.
 * In addition, the PlatformLogger API can be used if the logging
 * module does not exist.
 *
 * If the logging facility is not enabled, the platform loggers
 * will output log messages per the default logging configuration
 * (see below). In this implementation, it does not log the
 * the stack frame information issuing the log message.
 *
 * When the logging facility is enabled (at startup or runtime),
 * the java.util.logging.Logger will be created for each platform
 * logger and all log messages will be forwarded to the Logger
 * to handle.
 *
 * Logging facility is "enabled" when one of the following
 * conditions is met:
 * 1) a system property "java.util.logging.config.class" or
 *    "java.util.logging.config.file" is set
 * 2) java.util.logging.LogManager or java.util.logging.Logger
 *    is referenced that will trigger the logging initialization.
 *
 * Default logging configuration:
 *   global logging level = INFO
 *   handlers = java.util.logging.ConsoleHandler
 *   java.util.logging.ConsoleHandler.level = INFO
 *   java.util.logging.ConsoleHandler.formatter = java.util.logging.SimpleFormatter
 *
 * Limitation:
 * <JAVA_HOME>/lib/logging.properties is the system-wide logging
 * configuration defined in the specification and read in the
 * default case to configure any java.util.logging.Logger instances.
 * Platform loggers will not detect if <JAVA_HOME>/lib/logging.properties
 * is modified. In other words, unless the java.util.logging API
 * is used at runtime or the logging system properties is set,
 * the platform loggers will use the default setting described above.
 * The platform loggers are designed for JDK developers use and
 * this limitation can be workaround with setting
 * -Djava.util.logging.config.file system property.
 *
 * @since 1.7
 */
public class PlatformLogger {
    // shortcut to PlatformLogger.Level enums
    public static final Level OFF     = Level.OFF;
    public static final Level SEVERE  = Level.SEVERE;
    public static final Level WARNING = Level.WARNING;
    public static final Level INFO    = Level.INFO;
    public static final Level CONFIG  = Level.CONFIG;
    public static final Level FINE    = Level.FINE;
    public static final Level FINER   = Level.FINER;
    public static final Level FINEST  = Level.FINEST;
    public static final Level ALL     = Level.ALL;

    /**
     * PlatformLogger logging levels.
     */
    public static enum Level {
        // The name and value must match that of {@code java.util.logging.Level} objects.
        ALL(Integer.MIN_VALUE),
        FINEST(300),
        FINER(400),
        FINE(500),
        CONFIG(700),
        INFO(800),
        WARNING(900),
        SEVERE(1000),
        OFF(Integer.MAX_VALUE);

        /**
         * Associated java.util.logging.Level lazily initialized in
         * JavaLoggerProxy's static initializer only once
         * when java.util.logging is available and enabled.
         * Only accessed by JavaLoggerProxy.
         */
        /* java.util.logging.Level */ Object javaLevel;

        private final int value;
        public int intValue() {
            return value;
        }

        Level(int value) {
            this.value = value;
        }
    }

    private static final Level DEFAULT_LEVEL = INFO;
    private static boolean loggingEnabled;
    static {
        loggingEnabled = AccessController.doPrivileged(
            new PrivilegedAction<Boolean>() {
                public Boolean run() {
                    String cname = System.getProperty("java.util.logging.config.class");
                    String fname = System.getProperty("java.util.logging.config.file");
                    return (cname != null || fname != null);
                }
            });

        // force loading of all JavaLoggerProxy (sub)classes to make JIT de-optimizations
        // less probable.  Don't initialize JavaLoggerProxy class since
        // java.util.logging may not be enabled.
        try {
            Class.forName("sun.util.logging.PlatformLogger$DefaultLoggerProxy",
                          false,
                          PlatformLogger.class.getClassLoader());
            Class.forName("sun.util.logging.PlatformLogger$JavaLoggerProxy",
                          false,   // do not invoke class initializer
                          PlatformLogger.class.getClassLoader());
        } catch (ClassNotFoundException ex) {
            throw new InternalError(ex);
        }
    }

    // Table of known loggers.  Maps names to PlatformLoggers.
    private static Map<String,WeakReference<PlatformLogger>> loggers =
        new HashMap<>();

    /**
     * Returns a PlatformLogger of a given name.
     */
    public static synchronized PlatformLogger getLogger(String name) {
        PlatformLogger log = null;
        WeakReference<PlatformLogger> ref = loggers.get(name);
        if (ref != null) {
            log = ref.get();
        }
        if (log == null) {
            log = new PlatformLogger(name);
            loggers.put(name, new WeakReference<>(log));
        }
        return log;
    }

    /**
     * Initialize java.util.logging.Logger objects for all platform loggers.
     * This method is called from LogManager.readPrimordialConfiguration().
     */
    public static synchronized void redirectPlatformLoggers() {
        if (loggingEnabled || !LoggingSupport.isAvailable()) return;

        loggingEnabled = true;
        for (Map.Entry<String, WeakReference<PlatformLogger>> entry : loggers.entrySet()) {
            WeakReference<PlatformLogger> ref = entry.getValue();
            PlatformLogger plog = ref.get();
            if (plog != null) {
                plog.redirectToJavaLoggerProxy();
            }
        }
    }

    /**
     * Creates a new JavaLoggerProxy and redirects the platform logger to it
     */
    private void redirectToJavaLoggerProxy() {
        DefaultLoggerProxy lp = DefaultLoggerProxy.class.cast(this.loggerProxy);
        JavaLoggerProxy jlp = new JavaLoggerProxy(lp.name, lp.level);
        // the order of assignments is important
        this.javaLoggerProxy = jlp;   // isLoggable checks javaLoggerProxy if set
        this.loggerProxy = jlp;
    }

    // DefaultLoggerProxy may be replaced with a JavaLoggerProxy object
    // when the java.util.logging facility is enabled
    private volatile LoggerProxy loggerProxy;
    // javaLoggerProxy is only set when the java.util.logging facility is enabled
    private volatile JavaLoggerProxy javaLoggerProxy;
    private PlatformLogger(String name) {
        if (loggingEnabled) {
            this.loggerProxy = this.javaLoggerProxy = new JavaLoggerProxy(name);
        } else {
            this.loggerProxy = new DefaultLoggerProxy(name);
        }
    }

    /**
     * A convenience method to test if the logger is turned off.
     * (i.e. its level is OFF).
     */
    public boolean isEnabled() {
        return loggerProxy.isEnabled();
    }

    /**
     * Gets the name for this platform logger.
     */
    public String getName() {
        return loggerProxy.name;
    }

    /**
     * Returns true if a message of the given level would actually
     * be logged by this logger.
     */
    public boolean isLoggable(Level level) {
        // performance-sensitive method: use two monomorphic call-sites
        JavaLoggerProxy jlp = javaLoggerProxy;
        return jlp != null ? jlp.isLoggable(level) : loggerProxy.isLoggable(level);
    }

    /**
     * Get the log level that has been specified for this PlatformLogger.
     * The result may be null, which means that this logger's
     * effective level will be inherited from its parent.
     *
     * This method is primarily for testing purpose and not recommended
     * to be used at runtime since it does not support custom j.u.l.Level.
     *
     * @return  this PlatformLogger's level
     *
     * @throw IllegalArgumentException if j.u.l.Logger is set to
     *        a custom j.u.l.Level when java.util.logging facility is enabled
     */
    public Level getLevel() {
        return loggerProxy.getLevel();
    }

    /**
     * Set the log level specifying which message levels will be
     * logged by this logger.  Message levels lower than this
     * value will be discarded.  The level value {@link #OFF}
     * can be used to turn off logging.
     * <p>
     * If the new level is null, it means that this node should
     * inherit its level from its nearest ancestor with a specific
     * (non-null) level value.
     *
     * @param newLevel the new value for the log level (may be null)
     */
    public void setLevel(Level newLevel) {
        loggerProxy.setLevel(newLevel);
    }

    /**
     * Logs a SEVERE message.
     */
    public void severe(String msg) {
        loggerProxy.doLog(SEVERE, msg);
    }

    public void severe(String msg, Throwable t) {
        loggerProxy.doLog(SEVERE, msg, t);
    }

    public void severe(String msg, Object... params) {
        loggerProxy.doLog(SEVERE, msg, params);
    }

    /**
     * Logs a WARNING message.
     */
    public void warning(String msg) {
        loggerProxy.doLog(WARNING, msg);
    }

    public void warning(String msg, Throwable t) {
        loggerProxy.doLog(WARNING, msg, t);
    }

    public void warning(String msg, Object... params) {
        loggerProxy.doLog(WARNING, msg, params);
    }

    /**
     * Logs an INFO message.
     */
    public void info(String msg) {
        loggerProxy.doLog(INFO, msg);
    }

    public void info(String msg, Throwable t) {
        loggerProxy.doLog(INFO, msg, t);
    }

    public void info(String msg, Object... params) {
        loggerProxy.doLog(INFO, msg, params);
    }

    /**
     * Logs a CONFIG message.
     */
    public void config(String msg) {
        loggerProxy.doLog(CONFIG, msg);
    }

    public void config(String msg, Throwable t) {
        loggerProxy.doLog(CONFIG, msg, t);
    }

    public void config(String msg, Object... params) {
        loggerProxy.doLog(CONFIG, msg, params);
    }

    /**
     * Logs a FINE message.
     */
    public void fine(String msg) {
        loggerProxy.doLog(FINE, msg);
    }

    public void fine(String msg, Throwable t) {
        loggerProxy.doLog(FINE, msg, t);
    }

    public void fine(String msg, Object... params) {
        loggerProxy.doLog(FINE, msg, params);
    }

    /**
     * Logs a FINER message.
     */
    public void finer(String msg) {
        loggerProxy.doLog(FINER, msg);
    }

    public void finer(String msg, Throwable t) {
        loggerProxy.doLog(FINER, msg, t);
    }

    public void finer(String msg, Object... params) {
        loggerProxy.doLog(FINER, msg, params);
    }

    /**
     * Logs a FINEST message.
     */
    public void finest(String msg) {
        loggerProxy.doLog(FINEST, msg);
    }

    public void finest(String msg, Throwable t) {
        loggerProxy.doLog(FINEST, msg, t);
    }

    public void finest(String msg, Object... params) {
        loggerProxy.doLog(FINEST, msg, params);
    }

    /**
     * Abstract base class for logging support, defining the API and common field.
     */
    private static abstract class LoggerProxy {
        final String name;

        protected LoggerProxy(String name) {
            this.name = name;
        }

        abstract boolean isEnabled();

        abstract Level getLevel();
        abstract void setLevel(Level newLevel);

        abstract void doLog(Level level, String msg);
        abstract void doLog(Level level, String msg, Throwable thrown);
        abstract void doLog(Level level, String msg, Object... params);

        abstract boolean isLoggable(Level level);
    }


    private static final class DefaultLoggerProxy extends LoggerProxy {
        /**
         * Default platform logging support - output messages to System.err -
         * equivalent to ConsoleHandler with SimpleFormatter.
         */
        private static PrintStream outputStream() {
            return System.err;
        }

        volatile Level effectiveLevel; // effective level (never null)
        volatile Level level;          // current level set for this node (may be null)

        DefaultLoggerProxy(String name) {
            super(name);
            this.effectiveLevel = deriveEffectiveLevel(null);
            this.level = null;
        }

        boolean isEnabled() {
            return effectiveLevel != OFF;
        }

        Level getLevel() {
            return level;
        }

        void setLevel(Level newLevel) {
            Level oldLevel = level;
            if (oldLevel != newLevel) {
                level = newLevel;
                effectiveLevel = deriveEffectiveLevel(newLevel);
            }
        }

        void doLog(Level level, String msg) {
            if (isLoggable(level)) {
                outputStream().print(format(level, msg, null));
            }
        }

        void doLog(Level level, String msg, Throwable thrown) {
            if (isLoggable(level)) {
                outputStream().print(format(level, msg, thrown));
            }
        }

        void doLog(Level level, String msg, Object... params) {
            if (isLoggable(level)) {
                String newMsg = formatMessage(msg, params);
                outputStream().print(format(level, newMsg, null));
            }
        }

        boolean isLoggable(Level level) {
            Level effectiveLevel = this.effectiveLevel;
            return level.intValue() >= effectiveLevel.intValue() && effectiveLevel != OFF;
        }

        // derive effective level (could do inheritance search like j.u.l.Logger)
        private Level deriveEffectiveLevel(Level level) {
            return level == null ? DEFAULT_LEVEL : level;
        }

        // Copied from java.util.logging.Formatter.formatMessage
        private String formatMessage(String format, Object... parameters) {
            // Do the formatting.
            try {
                if (parameters == null || parameters.length == 0) {
                    // No parameters.  Just return format string.
                    return format;
                }
                // Is it a java.text style format?
                // Ideally we could match with
                // Pattern.compile("\\{\\d").matcher(format).find())
                // However the cost is 14% higher, so we cheaply check for
                // 1 of the first 4 parameters
                if (format.indexOf("{0") >= 0 || format.indexOf("{1") >=0 ||
                            format.indexOf("{2") >=0|| format.indexOf("{3") >=0) {
                    return java.text.MessageFormat.format(format, parameters);
                }
                return format;
            } catch (Exception ex) {
                // Formatting failed: use format string.
                return format;
            }
        }

        private static final String formatString =
            LoggingSupport.getSimpleFormat(false); // don't check logging.properties

        // minimize memory allocation
        private Date date = new Date();
        private synchronized String format(Level level, String msg, Throwable thrown) {
            date.setTime(System.currentTimeMillis());
            String throwable = "";
            if (thrown != null) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println();
                thrown.printStackTrace(pw);
                pw.close();
                throwable = sw.toString();
            }

            return String.format(formatString,
                                 date,
                                 getCallerInfo(),
                                 name,
                                 level.name(),
                                 msg,
                                 throwable);
        }

        // Returns the caller's class and method's name; best effort
        // if cannot infer, return the logger's name.
        private String getCallerInfo() {
            String sourceClassName = null;
            String sourceMethodName = null;

            JavaLangAccess access = SharedSecrets.getJavaLangAccess();
            Throwable throwable = new Throwable();
            int depth = access.getStackTraceDepth(throwable);

            String logClassName = "sun.util.logging.PlatformLogger";
            boolean lookingForLogger = true;
            for (int ix = 0; ix < depth; ix++) {
                // Calling getStackTraceElement directly prevents the VM
                // from paying the cost of building the entire stack frame.
                StackTraceElement frame =
                    access.getStackTraceElement(throwable, ix);
                String cname = frame.getClassName();
                if (lookingForLogger) {
                    // Skip all frames until we have found the first logger frame.
                    if (cname.equals(logClassName)) {
                        lookingForLogger = false;
                    }
                } else {
                    if (!cname.equals(logClassName)) {
                        // We've found the relevant frame.
                        sourceClassName = cname;
                        sourceMethodName = frame.getMethodName();
                        break;
                    }
                }
            }

            if (sourceClassName != null) {
                return sourceClassName + " " + sourceMethodName;
            } else {
                return name;
            }
        }
    }

    /**
     * JavaLoggerProxy forwards all the calls to its corresponding
     * java.util.logging.Logger object.
     */
    private static final class JavaLoggerProxy extends LoggerProxy {
        // initialize javaLevel fields for mapping from Level enum -> j.u.l.Level object
        static {
            for (Level level : Level.values()) {
                level.javaLevel = LoggingSupport.parseLevel(level.name());
            }
        }

        private final /* java.util.logging.Logger */ Object javaLogger;

        JavaLoggerProxy(String name) {
            this(name, null);
        }

        JavaLoggerProxy(String name, Level level) {
            super(name);
            this.javaLogger = LoggingSupport.getLogger(name);
            if (level != null) {
                // level has been updated and so set the Logger's level
                LoggingSupport.setLevel(javaLogger, level.javaLevel);
            }
        }

        void doLog(Level level, String msg) {
            LoggingSupport.log(javaLogger, level.javaLevel, msg);
        }

        void doLog(Level level, String msg, Throwable t) {
            LoggingSupport.log(javaLogger, level.javaLevel, msg, t);
        }

        void doLog(Level level, String msg, Object... params) {
            if (!isLoggable(level)) {
                return;
            }
            // only pass String objects to the j.u.l.Logger which may
            // be created by untrusted code
            int len = (params != null) ? params.length : 0;
            Object[] sparams = new String[len];
            for (int i = 0; i < len; i++) {
                sparams [i] = String.valueOf(params[i]);
            }
            LoggingSupport.log(javaLogger, level.javaLevel, msg, sparams);
        }

        boolean isEnabled() {
            return LoggingSupport.isLoggable(javaLogger, Level.OFF.javaLevel);
        }

        /**
         * Returns the PlatformLogger.Level mapped from j.u.l.Level
         * set in the logger.
         * @throw IllegalArgumentException if j.u.l.Logger is set to
         *        a custom j.u.l.Level
         */
        Level getLevel() {
            Object javaLevel = LoggingSupport.getLevel(javaLogger);
            return javaLevel == null
                    ? null
                    : Level.valueOf(LoggingSupport.getLevelName(javaLevel));
        }

        void setLevel(Level level) {
            LoggingSupport.setLevel(javaLogger, level == null ? null : level.javaLevel);
        }

        boolean isLoggable(Level level) {
            return LoggingSupport.isLoggable(javaLogger, level.javaLevel);
        }
    }
}
