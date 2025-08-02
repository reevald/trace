package com.trace.states;

import com.trace.contracts.wRDContract;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.BelongsToContract;
import net.corda.core.contracts.LinearState;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.identity.AbstractParty;
import net.corda.core.identity.Party;
import net.corda.core.serialization.ConstructorForDeserialization;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
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
    private final long version;
    private final Instant lastModified;

    @ConstructorForDeserialization
    public wRDAccountState(UniqueIdentifier walletId, Party owner, Party issuer, String tokenType,
                           Amount<Currency> tokenBalance, long version, Instant lastModified) {
        this.walletId = walletId;
        this.owner = owner;
        this.issuer = issuer;
        this.tokenType = tokenType;
        this.tokenBalance = tokenBalance;
        this.version = version;
        this.lastModified = lastModified;
    }

    public wRDAccountState(Party owner, Party issuer, String tokenType, Amount<Currency> tokenBalance) {
        this.walletId = new UniqueIdentifier();
        this.owner = owner;
        this.issuer = issuer;
        this.tokenType = tokenType;
        this.tokenBalance = tokenBalance;
        this.version = 1L;
        this.lastModified = Instant.now();
    }

    @NotNull
    @Override
    public UniqueIdentifier getLinearId() {
        return walletId;
    }

    @NotNull
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

    public long getVersion() {
        return version;
    }

    public Instant getLastModified() {
        return lastModified;
    }

    public wRDAccountState withNewBalanceAndIssuer(Amount<Currency> newBalance, Party newIssuer) {
        return new wRDAccountState(this.walletId, this.owner, newIssuer, this.tokenType, newBalance, this.version + 1,
                Instant.now());
    }
}