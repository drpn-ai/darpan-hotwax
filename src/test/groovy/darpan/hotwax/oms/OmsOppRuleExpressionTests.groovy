package darpan.hotwax.oms

import org.junit.jupiter.api.Test
import reconciliation.rule.FieldComparisonRuleLogicGenerator
import reconciliation.rule.RuleDiffSupport

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-064 Task 9. The presence OPP rule, exactly as it is entered on the gorjana presence run
 * (Shopify = file1, OMS = file2). Generated server-side on save, so this pins that the generator
 * accepts it and reads both emitted fields.
 *
 * ONE-DIRECTIONAL BY OPERATOR, not "=". P0 (2026-10-01, 1178 orders): paid-in-Shopify-without-OPP was
 * 0, while 104 $0 exchange orders carry an EXCHANGE_CREDIT OPP and no Shopify transaction. "=" would
 * report those 104 as findings. "<=" compares the strings, and "N" sorts before "Y", so ONLY
 * Shopify Y / OMS N violates.
 */
class OmsOppRuleExpressionTests {

    static final String EXPRESSION =
            '{"type":"FIELD_COMPARISON","file1FieldPath":"hasPaymentTransaction","file2FieldPath":"hasPaymentPreference","operator":"<="}'

    @Test
    void theGeneratorAcceptsTheExpressionAndReadsBothFields() {
        String drl = FieldComparisonRuleLogicGenerator.generate(EXPRESSION, null, null, "OMS_OPP_PRESENT", "WARN", 0)
        assertNotNull(drl, "generator rejected the expression")
        assertTrue(drl.contains('get("hasPaymentTransaction")'))
        assertTrue(drl.contains('get("hasPaymentPreference")'))
        assertTrue(drl.contains('RuleDiffSupport.violatesOperator('), "an ordered operator must route through RuleDiffSupport")
        assertTrue(drl.contains('"<="'))
    }

    @Test
    void onlyPaidInShopifyWithNoOppInOmsIsAFinding() {
        assertEquals(true, RuleDiffSupport.violatesOperator("Y", "N", "<="), "paid, no OPP: the finding")
        assertEquals(false, RuleDiffSupport.violatesOperator("Y", "Y", "<="))
        assertEquals(false, RuleDiffSupport.violatesOperator("N", "Y", "<="), "\$0 exchange order with an EXCHANGE_CREDIT OPP: not a finding")
        assertEquals(false, RuleDiffSupport.violatesOperator("N", "N", "<="))
    }
}
