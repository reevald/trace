package com.trace.states;

import net.corda.core.contracts.Amount;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.identity.CordaX500Name;
import net.corda.testing.core.TestIdentity;
import org.junit.Test;

import java.util.Currency;

import static org.junit.Assert.*;

public class wRDAccountStateTest {

    private final TestIdentity kdr = new TestIdentity(CordaX500Name.parse("O=KDR,L=Jakarta,C=ID"));
    private final TestIdentity wholesaler = new TestIdentity(CordaX500Name.parse("O=Wholesaler1,L=Jakarta,C=ID"));

    @Test
    public void testConstructorWithWalletId() {
        UniqueIdentifier walletId = new UniqueIdentifier();
        Amount<Currency> amount = new Amount<>(1000000L, Currency.getInstance("IDR"));
        
        wRDAccountState state = new wRDAccountState(walletId, kdr.getParty(), kdr.getParty(), "IDR", amount);
        
        assertEquals(walletId, state.getWalletId());
        assertEquals(kdr.getParty(), state.getOwner());
        assertEquals(kdr.getParty(), state.getIssuer());
        assertEquals("IDR", state.getTokenType());
        assertEquals(amount, state.getTokenBalance());
        assertEquals(walletId, state.getLinearId());
    }

    @Test
    public void testConstructorWithoutWalletId() {
        Amount<Currency> amount = new Amount<>(1000000L, Currency.getInstance("IDR"));
        
        wRDAccountState state = new wRDAccountState(wholesaler.getParty(), kdr.getParty(), "IDR", amount);
        
        assertNotNull(state.getWalletId());
        assertEquals(wholesaler.getParty(), state.getOwner());
        assertEquals(kdr.getParty(), state.getIssuer());
        assertEquals("IDR", state.getTokenType());
        assertEquals(amount, state.getTokenBalance());
        assertEquals(state.getWalletId(), state.getLinearId());
    }

    @Test
    public void testGetParticipants() {
        Amount<Currency> amount = new Amount<>(1000000L, Currency.getInstance("IDR"));
        wRDAccountState state = new wRDAccountState(wholesaler.getParty(), kdr.getParty(), "IDR", amount);
        
        assertEquals(2, state.getParticipants().size());
        assertTrue(state.getParticipants().contains(wholesaler.getParty()));
        assertTrue(state.getParticipants().contains(kdr.getParty()));
    }

    @Test
    public void testWithNewBalance() {
        Amount<Currency> originalAmount = new Amount<>(1000000L, Currency.getInstance("IDR"));
        Amount<Currency> newAmount = new Amount<>(2000000L, Currency.getInstance("IDR"));
        
        wRDAccountState originalState = new wRDAccountState(wholesaler.getParty(), kdr.getParty(), "IDR", originalAmount);
        wRDAccountState newState = originalState.withNewBalance(newAmount);
        
        assertEquals(originalState.getWalletId(), newState.getWalletId());
        assertEquals(originalState.getOwner(), newState.getOwner());
        assertEquals(originalState.getIssuer(), newState.getIssuer());
        assertEquals(originalState.getTokenType(), newState.getTokenType());
        assertEquals(newAmount, newState.getTokenBalance());
        assertNotEquals(originalAmount, newState.getTokenBalance());
    }

    @Test
    public void testParticipantsWithSameOwnerAndIssuer() {
        Amount<Currency> amount = new Amount<>(1000000L, Currency.getInstance("IDR"));
        wRDAccountState state = new wRDAccountState(kdr.getParty(), kdr.getParty(), "IDR", amount);
        
        assertEquals(2, state.getParticipants().size());
        assertEquals(kdr.getParty(), state.getParticipants().get(0));
        assertEquals(kdr.getParty(), state.getParticipants().get(1));
    }
}