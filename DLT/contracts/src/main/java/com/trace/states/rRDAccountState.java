package com.trace.states;

import com.trace.contracts.rRDContract;
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

@BelongsToContract(rRDContract.class)
public class rRDAccountState implements LinearState {
    private final UniqueIdentifier walletId;
    private final String owner;
    private final String issuer;
    private final String tokenType;
    private final Amount<Currency> tokenBalance;
    private final Party wholesalerParty;

    @ConstructorForDeserialization
    public rRDAccountState(UniqueIdentifier walletId, String owner, String issuer, String tokenType, 
                          Amount<Currency> tokenBalance, Party wholesalerParty) {
        this.walletId = walletId;
        this.owner = owner;
        this.issuer = issuer;
        this.tokenType = tokenType;
        this.tokenBalance = tokenBalance;
        this.wholesalerParty = wholesalerParty;
    }

    public rRDAccountState(String owner, String issuer, String tokenType, 
                          Amount<Currency> tokenBalance, Party wholesalerParty) {
        this.walletId = new UniqueIdentifier();
        this.owner = owner;
        this.issuer = issuer;
        this.tokenType = tokenType;
        this.tokenBalance = tokenBalance;
        this.wholesalerParty = wholesalerParty;
    }

    @Override
    public UniqueIdentifier getLinearId() {
        return walletId;
    }

    @Override
    public List<AbstractParty> getParticipants() {
        return Arrays.asList(wholesalerParty);
    }

    public UniqueIdentifier getWalletId() {
        return walletId;
    }

    public String getOwner() {
        return owner;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getTokenType() {
        return tokenType;
    }

    public Amount<Currency> getTokenBalance() {
        return tokenBalance;
    }

    public Party getWholesalerParty() {
        return wholesalerParty;
    }

    public rRDAccountState withNewBalance(Amount<Currency> newBalance) {
        return new rRDAccountState(this.walletId, this.owner, this.issuer, this.tokenType, newBalance, this.wholesalerParty);
    }

    public String getWholesalerFromOwner() {
        if (owner.contains("-")) {
            return owner.split("-")[0];
        }
        return owner;
    }

    public boolean isPeritel() {
        return owner.contains("-P-");
    }

    public boolean isRetail() {
        return owner.contains("-R-");
    }
}