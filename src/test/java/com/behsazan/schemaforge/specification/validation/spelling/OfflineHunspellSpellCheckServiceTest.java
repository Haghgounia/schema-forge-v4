package com.behsazan.schemaforge.specification.validation.spelling;

import com.behsazan.schemaforge.config.SpellCheckProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contracts for the network-free, Hunspell-dictionary-backed identifier advisor. */
class OfflineHunspellSpellCheckServiceTest {
    @Test
    void suggestsCustomerForMisspelledCustomer() {
        SpellCheckService service = service();
        List<SpellingError> errors = service.check("CUSTMER_NAME");
        assertEquals(1, errors.size());
        assertEquals("CUSTMER", errors.getFirst().word());
        assertEquals("CUSTOMER", errors.getFirst().suggestions().getFirst().value());
        assertFalse(errors.getFirst().serviceFailure());
    }

    @Test
    void recognizesMissingLettersAndAdjacentTranspositions() {
        SpellCheckService service = service();
        assertEquals("CUSTOMER", service.check("CUTOMER").getFirst().suggestions().getFirst().value());
        assertEquals("TRANSACTION", service.check("TRANSACTOIN_ID").getFirst().suggestions().getFirst().value());
        assertEquals("PROVINCE", service.check("PROVINC_NAME").getFirst().suggestions().getFirst().value());
        assertEquals("BENEFICIARY", service.check("BENEFCIARY_ID").getFirst().suggestions().getFirst().value());
        assertEquals("WITHDRAWAL", service.check("WITHDRAWL_AMOUNT").getFirst().suggestions().getFirst().value());
    }

    @Test
    void handlesEnglishInflectionsWithoutFalseWarnings() {
        SpellCheckService service = service();
        assertTrue(service.check("DEPOSIT_ACCOUNT_BALANCE").isEmpty());
        assertTrue(service.check("CUSTOMER_PROFILES").isEmpty());
        assertTrue(service.check("CREATED_DATE").isEmpty());
        assertTrue(service.check("TRANSACTIONS").isEmpty());
        assertTrue(service.check("RECONCILIATION").isEmpty());
    }

    @Test
    void respectsTechnicalAndDomainExclusionsWithoutTreatingThemAsApprovedGlossary() {
        SpellCheckProperties properties = enabledProperties();
        properties.setTechnicalTerms(List.of("IBAN", "IDEMPOTENCY", "JSON", "GRANTOR", "NOSTRO"));
        SpellCheckService service = new OfflineHunspellSpellCheckService(properties);
        assertTrue(service.check("IBAN_IDEMPOTENCY_JSON_GRANTOR_NOSTRO").isEmpty());
        // Technical exclusions suppress spelling warnings only. Governance is separate.
        assertEquals("CUSTOMER", service.check("CUSTMER").getFirst().suggestions().getFirst().value());
    }

    @Test
    void shortCodesNumericalIdentifiersAndUnmatchedNewTermsDoNotTriggerTypos() {
        SpellCheckService service = service();
        assertTrue(service.check("ID_PK_V2_2026").isEmpty());
        assertTrue(service.check("ZZZXYYQWWP").isEmpty());
    }

    @Test
    void alwaysPreservesIdentifierAndCachesEquivalentResults() {
        SpellCheckService service = service();
        String identifier = "CUSTMER_NAME";
        List<SpellingError> initial = service.check(identifier);
        assertEquals(initial, service.check(identifier));
        assertEquals("CUSTMER_NAME", identifier);
        assertTrue(initial.stream().noneMatch(SpellingError::serviceFailure));
    }

    @Test
    void disabledModeDoesNotRequireAnyDictionaryOrExternalConnection() {
        SpellCheckProperties properties = new SpellCheckProperties();
        assertTrue(new OfflineHunspellSpellCheckService(properties).check("CUSTMER").isEmpty());
    }

    @Test
    void distanceSupportsTranspositionAndRejectsFarMisspellings() {
        assertEquals(0, OfflineHunspellSpellCheckService.distanceAtMost("customer", "customer", 2));
        assertEquals(1, OfflineHunspellSpellCheckService.distanceAtMost("cutomer", "customer", 2));
        assertEquals(1, OfflineHunspellSpellCheckService.distanceAtMost("transactoin", "transaction", 2));
        assertTrue(OfflineHunspellSpellCheckService.distanceAtMost("customer", "permission", 2) > 2);
    }

    private static SpellCheckService service() {
        return new OfflineHunspellSpellCheckService(enabledProperties());
    }

    private static SpellCheckProperties enabledProperties() {
        SpellCheckProperties properties = new SpellCheckProperties();
        properties.setEnabled(true);
        properties.setMaximumSuggestions(3);
        return properties;
    }
}
