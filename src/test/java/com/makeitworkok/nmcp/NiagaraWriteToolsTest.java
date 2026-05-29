// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import javax.baja.sys.BComponent;
import javax.baja.sys.Context;
import javax.baja.sys.BValue;
import javax.baja.sys.Property;
import javax.baja.sys.Type;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NiagaraWriteToolsTest {

    private NiagaraSecurity readOnlySecurity() {
        return new NiagaraSecurity(true, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    private NiagaraSecurity writeSecurity() {
        return new NiagaraSecurity(false, true, 100,
                Arrays.asList("station:|slot:/Drivers", "station:|slot:/Services", "station:|slot:/Config"));
    }

    // -------------------------------------------------------------------------
    // toolNames / registration
    // -------------------------------------------------------------------------

    @Test
    void toolNames_areCorrect() {
        NiagaraWriteTools tools = new NiagaraWriteTools(readOnlySecurity());
        List<McpTool> list = tools.tools();
        assertEquals(5, list.size());
        assertEquals("nmcp.point.write",              list.get(0).name());
        assertEquals("nmcp.point.override",           list.get(1).name());
        assertEquals("nmcp.component.invokeAction",   list.get(2).name());
        assertEquals("nmcp.station.restart",          list.get(3).name());
        assertEquals("nmcp.driver.discoverAndAdd",    list.get(4).name());
    }

    // -------------------------------------------------------------------------
    // nmcp.point.write — security checks
    // -------------------------------------------------------------------------

    @Test
    void pointWrite_rejectsWhenReadOnly() {
        NiagaraWriteTools tools = new NiagaraWriteTools(readOnlySecurity());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("ord", "station:|slot:/Drivers/NumericPoint");
        args.put("value", 42.0);
        McpToolResult result = tools.tools().get(0).call(args, null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("read-only")
                || result.getErrorMessage().toLowerCase().contains("readonly"));
    }

    @Test
    void pointWrite_rejectsWhenOrdMissing() {
        NiagaraWriteTools tools = new NiagaraWriteTools(writeSecurity());
        McpToolResult result = tools.tools().get(0).call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("ord"));
    }

    @Test
    void pointWrite_rejectsWhenOrdNotAllowlisted() {
        NiagaraWriteTools tools = new NiagaraWriteTools(writeSecurity());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("ord", "station:|slot:/Hidden/NumericPoint");
        args.put("value", 42.0);
        McpToolResult result = tools.tools().get(0).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void pointWrite_inputSchema_requiresOrd() {
        NiagaraWriteTools tools = new NiagaraWriteTools(readOnlySecurity());
        String schema = tools.tools().get(0).inputSchema();
        assertTrue(schema.contains("\"ord\""));
        assertTrue(schema.contains("\"required\""));
    }

    @Test
    void pointWrite_description_mentionsReadOnly() {
        NiagaraWriteTools tools = new NiagaraWriteTools(readOnlySecurity());
        String desc = tools.tools().get(0).description();
        assertTrue(desc.toLowerCase().contains("readonly") || desc.contains("readOnly=false"));
    }

    // -------------------------------------------------------------------------
    // nmcp.point.override — security checks
    // -------------------------------------------------------------------------

    @Test
    void pointOverride_rejectsWhenReadOnly() {
        NiagaraWriteTools tools = new NiagaraWriteTools(readOnlySecurity());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("ord", "station:|slot:/Drivers/NumericPoint");
        args.put("value", true);
        McpToolResult result = tools.tools().get(1).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void pointOverride_rejectsWhenOrdMissing() {
        NiagaraWriteTools tools = new NiagaraWriteTools(writeSecurity());
        McpToolResult result = tools.tools().get(1).call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("ord"));
    }

    @Test
    void pointOverride_rejectsWhenOrdNotAllowlisted() {
        NiagaraWriteTools tools = new NiagaraWriteTools(writeSecurity());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("ord", "station:|slot:/Hidden/BoolPoint");
        args.put("value", false);
        McpToolResult result = tools.tools().get(1).call(args, null);
        assertTrue(result.isError());
    }

    // -------------------------------------------------------------------------
    // nmcp.component.invokeAction — security checks
    // -------------------------------------------------------------------------

    @Test
    void invokeAction_rejectsWhenReadOnly() {
        NiagaraWriteTools tools = new NiagaraWriteTools(readOnlySecurity());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("ord", "station:|slot:/Drivers/MyDevice");
        args.put("action", "enable");
        McpToolResult result = tools.tools().get(2).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void invokeAction_rejectsWhenOrdMissing() {
        NiagaraWriteTools tools = new NiagaraWriteTools(writeSecurity());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("action", "enable");
        McpToolResult result = tools.tools().get(2).call(args, null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("ord"));
    }

    @Test
    void invokeAction_rejectsWhenActionMissing() {
        NiagaraWriteTools tools = new NiagaraWriteTools(writeSecurity());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("ord", "station:|slot:/Drivers/MyDevice");
        McpToolResult result = tools.tools().get(2).call(args, null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("action"));
    }

    @Test
    void invokeAction_rejectsWhenOrdNotAllowlisted() {
        NiagaraWriteTools tools = new NiagaraWriteTools(writeSecurity());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("ord", "station:|slot:/Hidden/Device");
        args.put("action", "disable");
        McpToolResult result = tools.tools().get(2).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void invokeAction_inputSchema_requiresOrdAndAction() {
        NiagaraWriteTools tools = new NiagaraWriteTools(readOnlySecurity());
        String schema = tools.tools().get(2).inputSchema();
        assertTrue(schema.contains("\"ord\""));
        assertTrue(schema.contains("\"action\""));
        assertTrue(schema.contains("\"required\""));
    }

    @Test
    void invokeAction_prefersActionSlotFallback() throws Exception {
        NiagaraWriteTools tools = new NiagaraWriteTools(writeSecurity());
        Method method = NiagaraWriteTools.class.getDeclaredMethod("invokeComponentAction", BComponent.class, String.class, Object.class, Context.class);
        method.setAccessible(true);

        FakeActionSlot slot = new FakeActionSlot("toggle");
        FakeActionComponent component = new FakeActionComponent(slot);

        String result = (String) method.invoke(tools, component, "toggle", null, null);

        assertTrue(slot.invoked);
        assertTrue(result.contains("action slot"));
    }

    @Test
    void invokeAction_releaseUsesDedicatedOverridePath() throws Exception {
        NiagaraWriteTools tools = new NiagaraWriteTools(writeSecurity());
        Method method = NiagaraWriteTools.class.getDeclaredMethod("invokeComponentAction", BComponent.class, String.class, Object.class, Context.class);
        method.setAccessible(true);

        FakeReleaseComponent component = new FakeReleaseComponent();
        String result = (String) method.invoke(tools, component, "release", null, null);

        assertEquals("in8", component.lastSlot);
        assertTrue(result.contains("released override via in8"));
    }

    @Test
    void invokeAction_activeUsesDefaultArgumentForSingleParamMethod() throws Exception {
        NiagaraWriteTools tools = new NiagaraWriteTools(writeSecurity());
        Method method = NiagaraWriteTools.class.getDeclaredMethod("invokeComponentAction", BComponent.class, String.class, Object.class, Context.class);
        method.setAccessible(true);

        FakeActiveComponent component = new FakeActiveComponent();
        String result = (String) method.invoke(tools, component, "active", null, null);

        assertTrue(component.invoked);
        assertNotNull(component.arg);
        assertTrue(result.contains("active(Value)"));
    }

    // -------------------------------------------------------------------------
    // nmcp.station.restart — security checks
    // -------------------------------------------------------------------------

    @Test
    void stationRestart_rejectsWhenReadOnly() {
        NiagaraWriteTools tools = new NiagaraWriteTools(readOnlySecurity());
        McpToolResult result = tools.tools().get(3).call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
    }

    @Test
    void stationRestart_acceptsOptionalReason() {
        NiagaraWriteTools tools = new NiagaraWriteTools(writeSecurity());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("reason", "maintenance window");
        // No real station — will succeed/fail gracefully but not throw
        McpToolResult result = tools.tools().get(3).call(args, null);
        assertNotNull(result);
    }

    @Test
    void stationRestart_description_mentionsDestructive() {
        NiagaraWriteTools tools = new NiagaraWriteTools(readOnlySecurity());
        String desc = tools.tools().get(3).description();
        assertTrue(desc.toLowerCase().contains("restart") || desc.toLowerCase().contains("destructive"));
    }

    // -------------------------------------------------------------------------
    // nmcp.driver.discoverAndAdd — security checks
    // -------------------------------------------------------------------------

    @Test
    void driverDiscoverAndAdd_rejectsWhenReadOnly() {
        NiagaraWriteTools tools = new NiagaraWriteTools(readOnlySecurity());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("networkOrd", "station:|slot:/Drivers/BacnetNetwork");
        McpToolResult result = tools.tools().get(4).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void driverDiscoverAndAdd_rejectsWhenNetworkOrdMissing() {
        NiagaraWriteTools tools = new NiagaraWriteTools(writeSecurity());
        McpToolResult result = tools.tools().get(4).call(Collections.<String, Object>emptyMap(), null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("networkord"));
    }

    @Test
    void driverDiscoverAndAdd_rejectsWhenOrdNotAllowlisted() {
        NiagaraWriteTools tools = new NiagaraWriteTools(writeSecurity());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("networkOrd", "station:|slot:/Hidden/BacnetNetwork");
        McpToolResult result = tools.tools().get(4).call(args, null);
        assertTrue(result.isError());
    }

    @Test
    void driverDiscoverAndAdd_inputSchema_requiresNetworkOrd() {
        NiagaraWriteTools tools = new NiagaraWriteTools(readOnlySecurity());
        String schema = tools.tools().get(4).inputSchema();
        assertTrue(schema.contains("\"networkOrd\""));
        assertTrue(schema.contains("\"required\""));
    }

    // -------------------------------------------------------------------------
    // ORD prefix normalisation
    // -------------------------------------------------------------------------

    @Test
    void pointWrite_stripsLocalPrefix() {
        NiagaraWriteTools tools = new NiagaraWriteTools(readOnlySecurity());
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("ord", "local:|station:|slot:/Drivers/NumericPoint");
        args.put("value", 1.0);
        // Should fail on read-only, not on allowlist (local: gets stripped)
        McpToolResult result = tools.tools().get(0).call(args, null);
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().toLowerCase().contains("read-only")
                || result.getErrorMessage().toLowerCase().contains("readonly"));
    }

    private static final class FakeActionComponent extends BComponent {
        private final FakeActionSlot slot;

        private FakeActionComponent(FakeActionSlot slot) {
            this.slot = slot;
        }

        @Override
        public Property getProperty(String name) {
            if (slot != null && slot.getName().equals(name)) {
                return slot;
            }
            return null;
        }
    }

    private static final class FakeReleaseComponent extends BComponent {
        private String lastSlot;
        private BValue lastValue;

        @Override
        public void set(String slotName, BValue value) {
            this.lastSlot = slotName;
            this.lastValue = value;
        }
    }

    private static final class FakeActionSlot implements Property {
        private final String name;
        private boolean invoked;

        private FakeActionSlot(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Type getType() {
            return null;
        }

        @Override
        public boolean isProperty() {
            return false;
        }

        @Override
        public boolean isAction() {
            return true;
        }

        @Override
        public javax.baja.sys.BValue getDefaultValue() {
            return null;
        }

        public void invoke(Context cx) {
            invoked = true;
        }
    }

    public static final class FakeOverrideArg {
        public FakeOverrideArg() {
        }
    }

    private static final class FakeActiveComponent extends BComponent {
        private boolean invoked;
        private FakeOverrideArg arg;

        public void active(FakeOverrideArg value) {
            this.invoked = true;
            this.arg = value;
        }
    }
}
