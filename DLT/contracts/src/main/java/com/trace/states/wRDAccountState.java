package com.trace.states;

import com.trace.contracts.rRDContract;
import com.trace.contracts.wRDContract;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.BelongsToContract;
import net.corda.core.contracts.LinearState;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.identity.AbstractParty;
import net.corda.core.identity.Party;
import net.corda.core.serialization.ConstructorForDeserialization;

import java.util.Arrays;
import java.util.Currency;
import java.util.List;

@BelongsToContract(wRDContract.class)
public class wRDAccountState implements LinearState {
    private final UniqueIdentifier walletId;
    private final Party owner;
    private final Party issuer;
    private final String tokenType;
    private final Amount<Currency> tokenBalance;

    @ConstructorForDeserialization
    public wRDAccountState(UniqueIdentifier walletId, Party owner, Party issuer, String tokenType, Amount<Currency> tokenBalance) {
        this.walletId = walletId;
        this.owner = owner;
        this.issuer = issuer;
        this.tokenType = tokenType;
        this.tokenBalance = tokenBalance;
    }

    public wRDAccountState(Party owner, Party issuer, String tokenType, Amount<Currency> tokenBalance) {
        this.walletId = new UniqueIdentifier();
        this.owner = owner;
        this.issuer = issuer;
        this.tokenType = tokenType;
        this.tokenBalance = tokenBalance;
    }

    @Override
    public UniqueIdentifier getLinearId() {
        return walletId;
    }

    @Override
    public List<AbstractParty> getParticipants() {
        return Arrays.asList(owner, issuer);
    }

    public UniqueIdentifier getWalletId() {
        return walletId;
    }

    public Party getOwner() {
        return owner;
    }

    public Party getIssuer() {
        return issuer;
    }

    public String getTokenType() {
        return tokenType;
    }

    public Amount<Currency> getTokenBalance() {
        return tokenBalance;
    }

    public wRDAccountState withNewBalance(Amount<Currency> newBalance) {
        return new wRDAccountState(this.walletId, this.owner, this.issuer, this.tokenType, newBalance);
    }
}