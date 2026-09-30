import com.sun.jdi.Bootstrap;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Host-JDK deterministic race test. Run from apps/android after test class compilation. */
public final class CloseRaceDebugger {
    public static void main(String[] args) throws Exception {
        List<String> source = Files.readAllLines(Path.of(
            "adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java"));
        int breakpointLine = -1;
        for (int index = 0; index < source.size(); index++) {
            if (source.get(index).contains("if (stale) clearReconfirmation();")) {
                breakpointLine = index + 1;
                break;
            }
        }
        if (breakpointLine < 0) throw new AssertionError("close boundary not found");
        String classes = "adminapp/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
            + ":adminapp/build/intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes";
        LaunchingConnector launcher = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> launch = launcher.defaultArguments();
        launch.get("main").setValue(
            "kr.co.hanium.dreamup.walksafe.admin.security.AdminLocalOperationCloseRaceProbe");
        launch.get("options").setValue("-cp " + classes);
        VirtualMachine vm = launcher.launch(launch);
        Process process = vm.process();
        BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream()));
        PrintWriter commands = new PrintWriter(process.getOutputStream(), true);
        ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
        prepare.addClassFilter(
            "kr.co.hanium.dreamup.walksafe.admin.security.AdminSecurityController$LocalOperation");
        prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        prepare.enable();
        boolean completed = false;
        try {
            while (!completed) {
                EventSet events = vm.eventQueue().remove(10_000L);
                if (events == null) throw new AssertionError("debug event deadline exceeded");
                boolean resumed = false;
                for (Event event : events) {
                    if (event instanceof ClassPrepareEvent prepared) {
                        ReferenceType type = prepared.referenceType();
                        BreakpointRequest breakpoint = vm.eventRequestManager().createBreakpointRequest(
                            type.locationsOfLine(breakpointLine).get(0));
                        breakpoint.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                        breakpoint.enable();
                    } else if (event instanceof BreakpointEvent stopped) {
                        stopped.request().disable();
                        ThreadReference worker = stopped.thread();
                        ObjectReference scope = worker.frame(0).thisObject();
                        ObjectReference controller = (ObjectReference) scope.getValue(
                            scope.referenceType().fieldByName("this$0"));
                        ObjectReference gate = (ObjectReference) controller.getValue(
                            controller.referenceType().fieldByName("lifecycleLock"));
                        boolean gateHeld = worker.ownedMonitors().contains(gate);
                        System.out.println("CLOSE_BOUNDARY lifecycle_monitor_held=" + gateHeld);
                        String ready = output.readLine();
                        if (!"READY".equals(ready)) throw new AssertionError("target did not become ready: " + ready);
                        commands.println("BACKGROUND");
                        if (gateHeld) {
                            // A correct atomic close must finish before background can acquire the gate.
                            events.resume();
                            resumed = true;
                        }
                        String background = output.readLine();
                        System.out.println(background);
                        if (background == null || !background.startsWith("BACKGROUND retained=")) {
                            throw new AssertionError("background transition did not finish");
                        }
                        if (!resumed) {
                            events.resume();
                            resumed = true;
                        }
                        commands.println("ASSERT");
                        // Let VM death complete instead of holding it in the event queue.
                        vm.dispose();
                        String result = output.readLine();
                        if (!process.waitFor(10, TimeUnit.SECONDS)) throw new AssertionError("target exit deadline exceeded");
                        String errors = new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        if (!errors.isBlank()) System.out.print(errors);
                        if (process.exitValue() != 0 || !"RESULT PASS".equals(result)) {
                            throw new AssertionError("close race reproduced: target exit=" + process.exitValue());
                        }
                        System.out.println(result);
                        completed = true;
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        throw new AssertionError("target exited before the close boundary");
                    }
                }
                if (!resumed) events.resume();
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
