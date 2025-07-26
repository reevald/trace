package com.trace.states;

import net.corda.core.contracts.Amount;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.identity.CordaX500Name;
import net.corda.testing.core.TestIdentity;
import org.junit.Test;

import java.util.Currency;

import static org.junit.Assert.*;

public class rRDAccountStateTest {

    private final TestIdentity wholesaler = new TestIdentity(CordaX500Name.parse("O=Wholesaler1,L=Jakarta,C=ID"));

    @Test
    public void testConstructorWithWalletId() {
        UniqueIdentifier walletId = new UniqueIdentifier();
        Amount<Currency> amount = new Amount<>(1000000L, Currency.getInstance("IDR"));
        
        rRDAccountState state = new rRDAccountState(walletId, "Wholesaler1-P-peritel-001", "KDR", "IDR", amount, wholesaler.getParty());
        
        assertEquals(walletId, state.getWalletId());
        assertEquals("Wholesaler1-P-peritel-001", state.getOwner());
        assertEquals("KDR", state.getIssuer());
        assertEquals("IDR", state.getTokenType());
        assertEquals(amount, state.getTokenBalance());
        assertEquals(wholesaler.getParty(), state.getWholesalerParty());
        assertEquals(walletId, state.getLinearId());
    }

    @Test
    public void testConstructorWithoutWalletId() {
        Amount<Currency> amount = new Amount<>(1000000L, Currency.getInstance("IDR"));
        
        rRDAccountState state = new rRDAccountState("Wholesaler1-P-peritel-001", "KDR", "IDR", amount, wholesaler.getParty());
        
        assertNotNull(state.getWalletId());
        assertEquals("Wholesaler1-P-peritel-001", state.getOwner());
        assertEquals("KDR", state.getIssuer());
        assertEquals("IDR", state.getTokenType());
        assertEquals(amount, state.getTokenBalance());
        assertEquals(wholesaler.getParty(), state.getWholesalerParty());
        assertEquals(state.getWalletId(), state.getLinearId());
    }

    @Test
    public void testGetParticipants() {
        Amount<Currency> amount = new Amount<>(1000000L, Currency.getInstance("IDR"));
        rRDAccountState state = new rRDAccountState("Wholesaler1-P-peritel-001", "KDR", "IDR", amount, wholesaler.getParty());
        
        assertEquals(1, state.getParticipants().size());
        assertTrue(state.getParticipants().contains(wholesaler.getParty()));
    }

    @Test
    public void testWithNewBalance() {
        Amount<Currency> originalAmount = new Amount<>(1000000L, Currency.getInstance("IDR"));
        Amount<Currency> newAmount = new Amount<>(2000000L, Currency.getInstance("IDR"));
        
        rRDAccountState originalState = new rRDAccountState("Wholesaler1-P-peritel-001", "KDR", "IDR", originalAmount, wholesaler.getParty());
        rRDAccountState newState = originalState.withNewBalance(newAmount);
        
        assertEquals(originalState.getWalletId(), newState.getWalletId());
        assertEquals(originalState.getOwner(), newState.getOwner());
        assertEquals(originalState.getIssuer(), newState.getIssuer());
        assertEquals(originalState.getTokenType(), newState.getTokenType());
        assertEquals(originalState.getWholesalerParty(), newState.getWholesalerParty());
        assertEquals(newAmount, newState.getTokenBalance());
        assertNotEquals(originalAmount, newState.getTokenBalance());
    }

    @Test
    public void testGetWholesalerFromOwner() {
        Amount<Currency> amount = new Amount<>(1000000L, Currency.getInstance("IDR"));
        
        rRDAccountState peritelState = new rRDAccountState("Wholesaler1-P-peritel-001", "KDR", "IDR", amount, wholesaler.getParty());
        assertEquals("Wholesaler1", peritelState.getWholesalerFromOwner());
        
        rRDAccountState retailState = new rRDAccountState("Wholesaler1-R-retail-001", "KDR", "IDR", amount, wholesaler.getParty());
        assertEquals("Wholesaler1", retailState.getWholesalerFromOwner());
        
        rRDAccountState simpleState = new rRDAccountState("SimpleOwner", "KDR", "IDR", amount, wholesaler.getParty());
        assertEquals("SimpleOwner", simpleState.getWholesalerFromOwner());
    }

    @Test
    public void testIsPeritel() {
        Amount<Currency> amount = new Amount<>(1000000L, Currency.getInstance("IDR"));
        
        rRDAccountState peritelState = new rRDAccountState("Wholesaler1-P-peritel-001", "KDR", "IDR", amount, wholesaler.getParty());
        assertTrue(peritelState.isPeritel());
        
        rRDAccountState retailState = new rRDAccountState("Wholesaler1-R-retail-001", "KDR", "IDR", amount, wholesaler.getParty());
        assertFalse(retailState.isPeritel());
        
        rRDAccountState simpleState = new rRDAccountState("SimpleOwner", "KDR", "IDR", amount, wholesaler.getParty());
        assertFalse(simpleState.isPeritel());
    }

    @Test
    public void testIsRetail() {
        Amount<Currency> amount = new Amount<>(1000000L, Currency.getInstance("IDR"));
        
        rRDAccountState retailState = new rRDAccountState("Wholesaler1-R-retail-001", "KDR", "IDR", amount, wholesaler.getParty());
        assertTrue(retailState.isRetail());
        
        rRDAccountState peritelState = new rRDAccountState("Wholesaler1-P-peritel-001", "KDR", "IDR", amount, wholesaler.getParty());
        assertFalse(peritelState.isRetail());
        
        rRDAccountState simpleState = new rRDAccountState("SimpleOwner", "KDR", "IDR", amount, wholesaler.getParty());
        assertFalse(simpleState.isRetail());
    }
}