// Copyright (c) 2026 Chris Favre. This cover is licensed under the MIT License.
package com.makeitworkok.nmcp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link NiagaraSecurity}.
 */
class NiagaraSecurityTest {

    private NiagaraSecurity security(String... roots) {
        List<String> rootList = new ArrayList<>();
        for (String r : roots) rootList.add(r);
        return new NiagaraSecurity(true, true, 500, rootList);
    }

    // -------------------------------------------------------------------------
    // checkAllowlist
    // -------------------------------------------------------------------------

    @Test
    void checkAllowlist_exactMatch_passes() {
        NiagaraSecurity sec = security("station:|slot:/Drivers");
        assertDoesNotThrow(() -> sec.checkAllowlist("station:|slot:/Drivers"));
    }

    @Test
    void checkAllowlist_childPath_passes() {
        NiagaraSecurity sec = security("station:|slot:/Drivers");
        assertDoesNotThrow(() ->
                sec.checkAllowlist("station:|slot:/Drivers/BacnetNetwork"));
    }

    @Test
    void checkAllowlist_outsideRoots_throws() {
        NiagaraSecurity sec = security("station:|slot:/Drivers");
        NiagaraSecurity.McpSecurityException ex = assertThrows(
                NiagaraSecurity.McpSecurityException.class,
                () -> sec.checkAllowlist("station:|slot:/Hidden/Secret"));
        assertEquals(McpErrors.PATH_NOT_ALLOWLISTED, ex.getCode());
    }

    @Test
    void checkAllowlist_nullOrd_throws() {
        NiagaraSecurity sec = security("station:|slot:/Drivers");
        NiagaraSecurity.McpSecurityException ex = assertThrows(
                NiagaraSecurity.McpSecurityException.class,
                () -> sec.checkAllowlist(null));
        assertEquals(McpErrors.INVALID_PARAMS, ex.getCode());
    }

    @Test
    void checkAllowlist_emptyOrd_throws() {
        NiagaraSecurity sec = security("station:|slot:/Drivers");
        assertThrows(NiagaraSecurity.McpSecurityException.class,
                () -> sec.checkAllowlist("   "));
    }

    @Test
    void checkAllowlist_multipleRoots_passes() {
        NiagaraSecurity sec = security(
                "station:|slot:/Drivers",
                "station:|slot:/Services",
                "station:|slot:/Config");
        assertDoesNotThrow(() -> sec.checkAllowlist("station:|slot:/Services/AlarmService"));
        assertDoesNotThrow(() -> sec.checkAllowlist("station:|slot:/Config/Platforms"));
    }

    // -------------------------------------------------------------------------
    // checkBqlQuery
    // -------------------------------------------------------------------------

    @Test
    void checkBqlQuery_validSelect_passes() {
        NiagaraSecurity sec = security("station:|slot:/Drivers");
        assertDoesNotThrow(() -> sec.checkBqlQuery("SELECT * FROM control:NumericPoint"));
    }

    @Test
    void checkBqlQuery_caseInsensitiveSelect_passes() {
        NiagaraSecurity sec = security("station:|slot:/Drivers");
        assertDoesNotThrow(() -> sec.checkBqlQuery("select displayName from baja:Component"));
    }

    @Test
    void checkBqlQuery_nonSelect_rejected() {
        NiagaraSecurity sec = security("station:|slot:/Drivers");
        NiagaraSecurity.McpSecurityException ex = assertThrows(
                NiagaraSecurity.McpSecurityException.class,
                () -> sec.checkBqlQuery("DELETE FROM baja:Component"));
        assertEquals(McpErrors.BQL_REJECTED, ex.getCode());
    }

    @Test
    void checkBqlQuery_mutationKeywordInSelect_rejected() {
        NiagaraSecurity sec = security("station:|slot:/Drivers");
        assertThrows(NiagaraSecurity.McpSecurityException.class,
                () -> sec.checkBqlQuery("SELECT * FROM control:Point SET value=42"));
    }

    @Test
    void checkBqlQuery_emptyQuery_rejected() {
        NiagaraSecurity sec = security("station:|slot:/Drivers");
        assertThrows(NiagaraSecurity.McpSecurityException.class,
                () -> sec.checkBqlQuery(""));
    }

    @Test
    void checkBqlQuery_bqlDisabled_rejected() {
        NiagaraSecurity sec = new NiagaraSecurity(true, false, 500,
                Arrays.asList("station:|slot:/Drivers"));
        NiagaraSecurity.McpSecurityException ex = assertThrows(
                NiagaraSecurity.McpSecurityException.class,
                () -> sec.checkBqlQuery("SELECT * FROM baja:Component"));
        assertEquals(McpErrors.BQL_REJECTED, ex.getCode());
    }

    // -------------------------------------------------------------------------
    // effectiveLimit
    // -------------------------------------------------------------------------

    @Test
    void effectiveLimit_returnsMaxWhenNullRequested() {
        NiagaraSecurity sec = new NiagaraSecurity(true, true, 100, new ArrayList<>());
        assertEquals(100, sec.effectiveLimit(null));
    }

    @Test
    void effectiveLimit_capsAtMax() {
        NiagaraSecurity sec = new NiagaraSecurity(true, true, 100, new ArrayList<>());
        assertEquals(100, sec.effectiveLimit(999));
    }

    @Test
    void effectiveLimit_honorsRequestedLimitBelowMax() {
        NiagaraSecurity sec = new NiagaraSecurity(true, true, 100, new ArrayList<>());
        assertEquals(50, sec.effectiveLimit(50));
    }

    @Test
    void effectiveLimit_zeroOrNegativeReturnsMax() {
        NiagaraSecurity sec = new NiagaraSecurity(true, true, 100, new ArrayList<>());
        assertEquals(100, sec.effectiveLimit(0));
        assertEquals(100, sec.effectiveLimit(-5));
    }

    // -------------------------------------------------------------------------
    // isSensitiveSlot
    // -------------------------------------------------------------------------

    @Test
    void isSensitiveSlot_passwordKeyword() {
        NiagaraSecurity sec = security();
        assertTrue(sec.isSensitiveSlot("password"));
        assertTrue(sec.isSensitiveSlot("userPassword"));
        assertTrue(sec.isSensitiveSlot("PASSWORD"));
    }

    @Test
    void isSensitiveSlot_tokenKeyword() {
        NiagaraSecurity sec = security();
        assertTrue(sec.isSensitiveSlot("apiToken"));
        assertTrue(sec.isSensitiveSlot("accessToken"));
    }

    @Test
    void isSensitiveSlot_nonSensitive() {
        NiagaraSecurity sec = security();
        assertFalse(sec.isSensitiveSlot("displayName"));
        assertFalse(sec.isSensitiveSlot("enabled"));
        assertFalse(sec.isSensitiveSlot("status"));
    }

    @Test
    void isSensitiveSlot_nullName() {
        NiagaraSecurity sec = security();
        assertFalse(sec.isSensitiveSlot(null));
    }

    @Test
    void maskedValue_constant() {
        assertEquals("***", NiagaraSecurity.maskedValue());
    }
}
