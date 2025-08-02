package com.trace.states;

import com.trace.contracts.rRDContract;
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

@BelongsToContract(rRDContract.class)
public class rRDAccountState implements LinearState {
    private final UniqueIdentifier walletId;
    private final String walletType;
    private final String owner;
    private final Party ownerWholesaler;
    private final String issuer;
    private final Party issuerWholesaler;
    private final String tokenType;
    private final Amount<Currency> tokenBalance;
    private final long version;
    private final Instant lastModified;

    @ConstructorForDeserialization
    public rRDAccountState(UniqueIdentifier walletId, String walletType, String owner, Party ownerWholesaler,
                           String issuer, Party issuerWholesaler, String tokenType, Amount<Currency> tokenBalance,
                           long version, Instant lastModified) {
        this.walletId = walletId;
        this.walletType = walletType;
        this.owner = owner;
        this.ownerWholesaler = ownerWholesaler;
        this.issuer = issuer;
        this.issuerWholesaler = issuerWholesaler;
        this.tokenType = tokenType;
        this.tokenBalance = tokenBalance;
        this.version = version;
        this.lastModified = lastModified;
    }

    public rRDAccountState(String walletType, String owner, Party ownerWholesaler, String issuer,
                           Party issuerWholesaler, String tokenType, Amount<Currency> tokenBalance) {
        this.walletId = new UniqueIdentifier();
        this.walletType = walletType;
        this.owner = owner;
        this.ownerWholesaler = ownerWholesaler;
        this.issuer = issuer;
        this.issuerWholesaler = issuerWholesaler;
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
        return Arrays.asList(ownerWholesaler, issuerWholesaler);
    }

    public UniqueIdentifier getWalletId() {
        return walletId;
    }

    public String getWalletType() {
        return walletType;
    }

    public String getOwner() {
        return owner;
    }

    public Party getOwnerWholesaler() {
        return ownerWholesaler;
    }

    public String getIssuer() {
        return issuer;
    }

    public Party getIssuerWholesaler() {
        return issuerWholesaler;
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

    public rRDAccountState withNewBalanceAndIssuer(Amount<Currency> newBalance, String issuer, Party issuerWholesaler) {
        return new rRDAccountState(this.walletId, this.walletType, this.owner, this.ownerWholesaler, issuer,
                issuerWholesaler, this.tokenType, newBalance, this.version + 1, Instant.now());
    }

    public boolean isRetailer() {
        return walletType.equals("RETAILER");
    }

    public boolean isRetail() {
        return walletType.equals("RETAIL");
    }
}