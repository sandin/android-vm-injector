package com.github.sandin.artinjector;

import com.sun.jdi.*;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.*;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.MethodEntryRequest;

import java.util.*;

/**
 * Debugger for Android VM (art and dalvik)
 */
public class ArtDebugger {

    private VirtualMachine mVirtualMachine;
    private boolean mAttached = false;

    private List<Breakpoint> mBreakpoints = new ArrayList<>();
    private Map<EventRequest, Breakpoint> mEventRequestMap = new HashMap<>();
    private List<EventListener> mEventListeners = new ArrayList<>();

    private Thread mEventMonitorThread = null;
    private volatile boolean mEventMonitorThreadRunning = false;

    public ArtDebugger() {
    }

    /**
     * Attach to a VM
     *
     * @param hostName vm hostname
     * @param port     vm debugger port
     * @param timeout  wait timeout
     * @return success/fail
     */
    public boolean attach(String hostName, int port, long timeout) {
        if (mAttached) {
            throw new IllegalStateException("attached!");
        }

        VirtualMachineManager vmm = Bootstrap.virtualMachineManager();
        List<AttachingConnector> connectors = vmm.attachingConnectors();
        AttachingConnector connector = connectors.get(0);
        // in JDK 10, the first AttachingConnector is not the one we want
        final String SUN_ATTACH_CONNECTOR = "com.sun.tools.jdi.SocketAttachingConnector";
        for (AttachingConnector con : connectors) {
            if (con.getClass().getName().equals(SUN_ATTACH_CONNECTOR)) {
                connector = con;
                break;
            }
        }

        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("hostname").setValue(hostName);
        arguments.get("port").setValue(String.valueOf(port));
        arguments.get("timeout").setValue(String.valueOf(timeout));
        for (Map.Entry<String, Connector.Argument> arg : arguments.entrySet()) {
            System.out.println("[Success] JDWP Connection arguments: " + arg.getValue());
        }

        try {
            mVirtualMachine = connector.attach(arguments);
            if (mVirtualMachine != null) {
                startEventMonitorThread();
                mAttached = true;
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }

        return mVirtualMachine != null;
    }

    public VirtualMachine getVirtualMachine() {
        return mVirtualMachine;
    }

    /**
     * Dispose
     */
    public void dispose() {
        stopEventMonitorThread(false);
        if (mVirtualMachine != null) {
            mVirtualMachine.dispose();
        }
    }

    /**
     * Invoke a method in remote VM
     *
     * @param evaluateContext context(thread)
     * @param className       class name
     * @param methodName      method name
     * @param args            method args
     * @return result
     */
    public EvaluateResult evaluateMethod(
            EvaluateContext evaluateContext, String className, String methodName, Object[] args) {
        EvaluateResult result = new EvaluateResult();

        assertVirtualMachine();

        List<ReferenceType> classes = mVirtualMachine.classesByName(className);
        ClassType cls = classes.size() > 0 ? (ClassType) classes.get(0) : null;
        if (cls == null) {
            result.setError("Can not find class " + className);
            return result;
        }
        System.out.println("[Success] Found class: " + cls.name() + ", " + cls.classLoader());

        List<Method> methods = cls.methodsByName(methodName);
        Method method = methods.size() > 0 ? methods.get(0) : null;
        if (method == null) {
            result.setError("Can not find method " + className + "." + methodName);
            return result;
        }
        System.out.println("[Success] Found method: " + method.name() + " " + method.signature());

        List<Value> methodArgs = new ArrayList<>();
        int options = ClassType.INVOKE_SINGLE_THREADED;
        for (Object arg : args) {
            if (arg instanceof String) {
                methodArgs.add(mVirtualMachine.mirrorOf((String) arg));
            } else if (arg instanceof Boolean) {
                methodArgs.add(mVirtualMachine.mirrorOf((Boolean) arg));
            } else {
                throw new IllegalArgumentException(
                        "unsupported args type: " + arg); // TODO: support other primitive type
            }
        }
        System.out.println(
                "[Success] try to invoke method: className="
                        + className
                        + ", methodName="
                        + methodName
                        + ", args="
                        + Arrays.toString(args));
        ThreadReference thread = evaluateContext.getThread();
        if (!thread.isSuspended()) {
            result.setError("Thread is not suspended!");
            return result;
        }

        Value invokeResult = null;
        try {
            invokeResult =
                    cls.invokeMethod(thread, method, methodArgs, options); // System.load(libpath);
            result.setResult(invokeResult);
            System.out.println(
                    "[Success] invoke method result: " + result + ", t=" + System.currentTimeMillis());
        } catch (InvocationException e) {
            System.out.println("[ErrorCode]: " + ErrorCodes.LOAD_SO_FAIL);
            System.err.println("[Error] invoke method fail, exception: " + e);
            ObjectReference exception = e.exception();
            e.printStackTrace();
            ArtInjectException remoteException = parseRemoteException(thread, exception);
            result.setError(remoteException.getMessage());
        } catch (ClassNotLoadedException e) {
            e.printStackTrace();
            result.setError(e.getMessage());
        } catch (IncompatibleThreadStateException e) {
            e.printStackTrace();
            result.setError(e.getMessage());
        } catch (InvalidTypeException e) {
            e.printStackTrace();
            result.setError(e.getMessage());
        }

        return result;
    }

    private ArtInjectException parseRemoteException(ThreadReference threadRef, ObjectReference remoteExceptionRef) {
        String message = "";

        ReferenceType type = remoteExceptionRef.referenceType();
        message += type.name() + " ";

        List<Method> methods = type.methodsByName("getMessage");
        Method getMessageMethod = methods.size() > 0 ? methods.get(0) : null;
        if (getMessageMethod != null) {
            try {
                List<Value> methodArgs = new ArrayList<>();
                StringReference value = (StringReference) remoteExceptionRef.invokeMethod(threadRef, getMessageMethod, methodArgs, ClassType.INVOKE_SINGLE_THREADED);
                message += value;
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }

        return new ArtInjectException(message);
    }

    private void startEventMonitorThread() {
        System.out.println("[Success] start event monitor thread");
        mEventMonitorThreadRunning = true;
        mEventMonitorThread =
                new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                assertVirtualMachine();
                                EventQueue eventQueue = mVirtualMachine.eventQueue();
                                boolean vmExit = false;

                                while (mEventMonitorThreadRunning && !vmExit) {
                                    try {
                                        EventSet eventSet = eventQueue.remove();
                                        EventIterator eventIterator = eventSet.eventIterator();
                                        while (eventIterator.hasNext()) {
                                            com.sun.jdi.event.Event event =
                                                    eventIterator.nextEvent();
                                            if (event instanceof VMDeathEvent) {
                                                vmExit = true;
                                            } else if (event instanceof VMDisconnectEvent) {
                                                vmExit = true;
                                            } else if (event instanceof MethodEntryEvent) {
                                                processMethodEntryEvent((MethodEntryEvent) event);
                                            } else if (event
                                                    instanceof com.sun.jdi.event.BreakpointEvent) {
                                                processBreakpointEventEvent(
                                                        (com.sun.jdi.event.BreakpointEvent) event);
                                            }
                                            eventSet.resume();
                                        }
                                    } catch (Throwable e) {
                                        e.printStackTrace();
                                    }

                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException ignore) {

                                    }
                                }
                                System.out.println("[Success] event monitor thread exit");
                            }
                        });
        mEventMonitorThread.start();
    }

    private void processMethodEntryEvent(MethodEntryEvent event) {
        //System.out.println("[✔] on method entry event " + event + ", method=" + event.method());
        EvaluateContext ctx = new EvaluateContext(event.thread(), event.method());

        EventRequest eventRequest = event.request();
        if (mEventRequestMap.containsKey(eventRequest)) {
            MethodBreakpoint breakpoint = (MethodBreakpoint) mEventRequestMap.get(eventRequest);
            Method method = event.method();
            if (method.name().equals(breakpoint.getMethodName())) {
                BreakpointEvent breakpointEvent = new BreakpointEvent(ctx, breakpoint);
                notifyEventListeners(breakpointEvent);
            }
        }
    }

    private void processBreakpointEventEvent(com.sun.jdi.event.BreakpointEvent event) {
        // TODO:
    }

    private void stopEventMonitorThread(boolean waitForStop) {
        System.out.println("[Success] stop event monitor thread");
        mEventMonitorThreadRunning = false;
        if (mEventMonitorThread != null) {
            if (waitForStop) {
                try {
                    mEventMonitorThread.join();
                } catch (InterruptedException ignore) {
                }
            }
            mEventMonitorThread = null;
        }
    }

    private void assertVirtualMachine() {
        if (mVirtualMachine == null) {
            throw new IllegalStateException("VirtualMachine is null");
        }
    }

    /**
     * Add a breakpoint
     *
     * @param breakpoint breakpoint to add
     */
    public void addBreakpoint(Breakpoint breakpoint) {
        assertVirtualMachine();
        if (breakpoint.enable(mVirtualMachine)) {
            mEventRequestMap.put(breakpoint.getEventRequest(), breakpoint);
            mBreakpoints.add(breakpoint);
        }
    }

    /**
     * Remove a breakpoint
     *
     * @param breakpoint breakpoint to remove
     */
    public void removeBreakpoint(Breakpoint breakpoint) {
        assertVirtualMachine();
        breakpoint.disable(mVirtualMachine);

        mEventRequestMap.remove(breakpoint.getEventRequest());
        mBreakpoints.remove(breakpoint);
    }

    /**
     * Clear and disable all breakpoints
     */
    public void clearBreakpoints() {
        assertVirtualMachine();

        Iterator<Breakpoint> it = mBreakpoints.iterator();
        while (it.hasNext()) {
            Breakpoint breakpoint = it.next();
            breakpoint.disable(mVirtualMachine);
            it.remove();
        }
    }

    /**
     * Get all enabled breakpoints
     *
     * @return breakpoints
     */
    public List<Breakpoint> getBreakpoints() {
        return mBreakpoints;
    }

    /**
     * Register Debug Event Listener
     *
     * @param l listener
     */
    public void registerEventListener(EventListener l) {
        mEventListeners.add(l);
    }

    /**
     * Unregister Debug Event Listener
     *
     * @param l listener
     */
    public void unregisterEventListener(EventListener l) {
        mEventListeners.remove(l);
    }

    private void notifyEventListeners(Event event) {
        for (EventListener l : mEventListeners) {
            boolean consumed = l.onEvent(event);
            if (consumed) {
                break;
            }
        }
    }

    private static ClassType findClass(VirtualMachine vm, String className) {
        List<ReferenceType> classes = vm.classesByName(className);
        if (classes != null && classes.size() > 0) {
            return (ClassType) classes.get(0);
        }
        return null;
    }

    /**
     * Breakpoint
     */
    public abstract static class Breakpoint {

        protected boolean mEnabled;
        protected int mSuspendPolicy;

        public void setEnabled(boolean enabled) {
            mEnabled = enabled;
        }

        public boolean isEnabled() {
            return mEnabled;
        }

        public void setSuspendPolicy(int suspendPolicy) {
            mSuspendPolicy = suspendPolicy;
        }

        public int getSuspendPolicy() {
            return mSuspendPolicy;
        }

        public abstract boolean enable(VirtualMachine vm);

        public abstract boolean disable(VirtualMachine vm);

        public abstract EventRequest getEventRequest();

        public static class Builder {
            public String className;
            public String methodName;
            public int suspendPolicy = EventRequest.SUSPEND_EVENT_THREAD;

            public Builder className(String className) {
                this.className = className;
                return this;
            }

            public Builder methodName(String methodName) {
                this.methodName = methodName;
                return this;
            }

            public Builder suspendPolicy(int suspendPolicy) {
                this.suspendPolicy = suspendPolicy;
                return this;
            }

            public Breakpoint build() {
                Breakpoint breakpoint = null;
                if (className != null && methodName != null) {
                    breakpoint = new MethodBreakpoint(className, methodName);
                    breakpoint.setEnabled(true);
                } else {
                    throw new IllegalArgumentException("className or methodName is null");
                }

                breakpoint.setSuspendPolicy(suspendPolicy);
                return breakpoint;
            }
        }
    }

    /**
     * Breakpoint for method
     */
    public static class MethodBreakpoint extends Breakpoint {
        private final String mClassName;
        private final String mMethodName;

        private MethodEntryRequest mMethodEntryRequest = null;

        public MethodBreakpoint(String className, String methodName) {
            super();
            mClassName = className;
            mMethodName = methodName;
        }

        public String getClassName() {
            return mClassName;
        }

        public String getMethodName() {
            return mMethodName;
        }

        public boolean enable(VirtualMachine vm) {
            if (mMethodEntryRequest == null) {
                EventRequestManager eventRequestMgr = vm.eventRequestManager();

                ClassType clsType = findClass(vm, mClassName);
                if (clsType == null) {
                    System.err.println("Can not find " + mClassName + " class");
                    return false;
                }

                mMethodEntryRequest = eventRequestMgr.createMethodEntryRequest();
                mMethodEntryRequest.addClassFilter(clsType);
                mMethodEntryRequest.setSuspendPolicy(mSuspendPolicy);
                mMethodEntryRequest.enable();
            }
            return true;
        }

        public boolean disable(VirtualMachine vm) {
            if (mMethodEntryRequest != null) {
                mMethodEntryRequest.disable();
                vm.eventRequestManager().deleteEventRequest(mMethodEntryRequest);
                mMethodEntryRequest = null;
            }
            return true;
        }

        @Override
        public EventRequest getEventRequest() {
            return mMethodEntryRequest;
        }

        @Override
        public String toString() {
            return "[MethodBreakpoint] className="
                    + mClassName
                    + ", methodName="
                    + mMethodName
                    + "]";
        }
    }

    /**
     * Evaluate Context (which thread)
     */
    public static class EvaluateContext {

        private ThreadReference mThread;
        private Method mMethod;

        public EvaluateContext(ThreadReference thread, Method method) {
            mThread = thread;
            mMethod = method;
        }

        public ThreadReference getThread() {
            return mThread;
        }

        public Method getMethod() {
            return mMethod;
        }
    }

    /**
     * Evaluate Result
     */
    public static class EvaluateResult {
        private Object mObject = null;
        private String mError = null;

        public void setResult(Object result) {
            mObject = result;
        }

        public Object getResult() {
            return mObject;
        }

        public String getError() {
            return mError;
        }

        public void setError(String error) {
            mError = error;
        }
    }

    /**
     * Debugger Event
     */
    public abstract static class Event {

        private EvaluateContext mEvaluateContext;

        public Event(EvaluateContext evaluateContext) {
            mEvaluateContext = evaluateContext;
        }

        public EvaluateContext getEvaluateContext() {
            return mEvaluateContext;
        }
    }

    /**
     * Breakpoint Event
     */
    public static class BreakpointEvent extends Event {

        private MethodBreakpoint mBreakpoint;

        public BreakpointEvent(EvaluateContext evaluateContext, MethodBreakpoint breakpoint) {
            super(evaluateContext);
            mBreakpoint = breakpoint;
        }

        public MethodBreakpoint getBreakpoint() {
            return mBreakpoint;
        }
    }

    /**
     * Listener for Debugger Event
     */
    public interface EventListener {

        /**
         * Event callback method
         *
         * @param event which event
         * @return consumed or not
         */
        boolean onEvent(Event event);
    }
}
