package com.trace.contracts;

import com.trace.states.rRDAccountState;
import com.trace.states.wRDAccountState;
import net.corda.core.contracts.*;
import net.corda.core.identity.AbstractParty;
import net.corda.core.identity.Party;
import net.corda.core.transactions.LedgerTransaction;
import org.jetbrains.annotations.NotNull;

import java.security.PublicKey;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static net.corda.core.contracts.ContractsDSL.requireThat;

public class RDContract implements Contract {
    public static final String ID = "com.trace.contracts.RDContract";

    @Override
    public void verify(@NotNull LedgerTransaction tx) {
        if (tx.getCommands().isEmpty()) {
            throw new IllegalArgumentException("No commands found");
        }
        final CommandWithParties<CommandData> command = tx.getCommands().get(0);
        final CommandData commandData = command.getValue();

        if (commandData instanceof Commands.wRDCentralInitCommand) {
            verifyWRDCentralInit(tx);
        } else if (commandData instanceof Commands.wRDIssuanceInitCommand) {
            verifyWRDIssuanceInit(tx);
        } else if (commandData instanceof Commands.wRDIssuanceCommand) {
            verifyWRDIssuance(tx);
        } else if (commandData instanceof Commands.wRDTransferCommand) {
            verifyWRDTransfer(tx);
        } else if (commandData instanceof Commands.wRDRedemptionCommand) {
            verifyWRDRedemption(tx);
        } else if (commandData instanceof Commands.wRD2rRDIssuanceInitCommand) {
            verifyWRD2RRDIssuanceInit(tx);
        } else {
            throw new IllegalArgumentException("Unrecognised command");
        }
    }

    private void verifyWRDCentralInit(LedgerTransaction tx) {
        requireThat(require -> {
            final List<wRDAccountState> outputs = tx.outputsOfType(wRDAccountState.class);
            // 1. No input states should be consumed
            require.using("No input states should be consumed", tx.getInputs().isEmpty());

            // 2. Only one output state should be created
            require.using("Only one output state should be created", outputs.size() == 1);

            final wRDAccountState output = outputs.get(0);

            // 3. Owner and issuer must be the same (KDR)
            require.using("Owner and issuer must be the same (KDR)", output.getOwner().equals(output.getIssuer()));

            // 4. Owner must be KDR
            require.using("Owner must be KDR", isKDR(output.getOwner()));

            // 5. Token balance must be greater than 0
            require.using("Token balance must be greater than 0", output.getTokenBalance().getQuantity() > 0);

            // 6. Token type must be IDR
            require.using("Token type must be IDR", output.getTokenType().equals("IDR"));

            // 7. Version must be greater than 0
            require.using("Version must be greater than 0", output.getVersion() > 0);

            // 8. Last Modified must be before now
            require.using("Last Modified must be before now", output.getLastModified().isBefore(Instant.now()));

            // 9. Single party KDR must sign wRDCentralInit transaction
            Set<PublicKey> listOfParticipantPublicKeys =
                    output.getParticipants().stream()
                            .map(AbstractParty::getOwningKey).collect(Collectors.toSet());
            List<PublicKey> arrayOfSigners = tx.getCommands().get(0).getSigners();
            Set<PublicKey> setOfSigners = new HashSet<>(arrayOfSigners);
            require.using("Single party KDR must sign wRDCentralInit transaction",
                    setOfSigners.equals(listOfParticipantPublicKeys) && setOfSigners.size() == 1);

            return null;
        });
    }

    private void verifyWRDIssuanceInit(LedgerTransaction tx) {
        requireThat(require -> {
            final List<wRDAccountState> inputs = tx.inputsOfType(wRDAccountState.class);
            final List<wRDAccountState> outputs = tx.outputsOfType(wRDAccountState.class);

            // 1. One input state should be consumed
            require.using("One input state should be consumed", inputs.size() == 1);

            // 2. Two output states should be created
            require.using("Two output states should be created", outputs.size() == 2);

            wRDAccountState sourceInput = inputs.get(0);

            // 3. Source's owner must be KDR
            require.using("Source's owner must be KDR", isKDR(sourceInput.getOwner()));

            wRDAccountState sourceOutput = null;
            wRDAccountState receiverOutput = null;

            for (wRDAccountState output : outputs) {
                if (isKDR(output.getOwner())) {
                    sourceOutput = output;
                } else {
                    receiverOutput = output;
                }
            }

            // 4. Must have KDR output (the owner must be equal both input and output)
            require.using("Must have KDR output", sourceOutput != null);
            require.using("Source wallet owner must be equal between input and output states",
                    sourceInput.getOwner().equals(sourceOutput.getOwner()));

            // 5. Must have non-KDR output
            require.using("Must have non-KDR output", receiverOutput != null);

            // 6. Receiver's owner must not be KDR
            require.using("Receiver's owner must be not KDR", !isKDR(receiverOutput.getOwner()));

            // 7. Source issuer in output state must be equal to receiver owner in output state
            require.using("Source's issuer in output state must be equal to receiver's owner in input state",
                    sourceOutput.getIssuer().equals(receiverOutput.getOwner()));

            // 8. Receiver issuer in output state must be equal to source owner in input state
            require.using("Receiver's issuer in output state must be equal to source's owner in input state",
                    receiverOutput.getIssuer().equals(sourceInput.getOwner()));

            Amount<Currency> transferAmount = sourceInput.getTokenBalance().minus(sourceOutput.getTokenBalance());

            // 9. Transfer amount must be greater than 0
            require.using("Transfer amount must be greater than 0", transferAmount.getQuantity() > 0);

            // 10. Source must have sufficient balance (note: overlap with previous check)
            require.using("Source must have sufficient balance",
                    sourceInput.getTokenBalance().getQuantity() >= transferAmount.getQuantity());

            // 11. Receiver token balance must be equal with transfer amount
            require.using("Receiver token balance must be equal with transfer amount",
                    receiverOutput.getTokenBalance().getQuantity() == transferAmount.getQuantity());

            // 12. Token type must be IDR
            require.using("Source's token type in input state must be IDR", sourceInput.getTokenType().equals("IDR"));
            require.using("Source's token type in output state must be IDR", sourceOutput.getTokenType().equals("IDR"));
            require.using("Receiver's token type in output state must be IDR", receiverOutput.getTokenType().equals(
                    "IDR"));

            // 13. Version source in output state must be +1 of source in input state
            require.using("Version source in output state must be +1 of source in input state",
                    sourceOutput.getVersion() == sourceInput.getVersion() + 1);

            // 14. Version receiver in output state must be greater than 0
            require.using("Version receiver in output state must be greater than 0", receiverOutput.getVersion() > 0);

            // 15. Last modified source in output state must be before now and after source in input state
            require.using("Last modified source in output state must be before now and after source in input state",
                    sourceOutput.getLastModified().isBefore(Instant.now()) &&
                            sourceOutput.getLastModified().isAfter(sourceInput.getLastModified()));

            // 16. Last Modified receiver in output state must be before now
            require.using("Last Modified receiverOutput must be before now",
                    receiverOutput.getLastModified().isBefore(Instant.now()));

            // 17. All participants must sign wRDIssuanceInit transaction
            Set<PublicKey> listOfParticipantPublicKeys =
                    sourceInput.getParticipants().stream()
                            .map(AbstractParty::getOwningKey).collect(Collectors.toSet());
            listOfParticipantPublicKeys.add(receiverOutput.getOwner().getOwningKey());
            List<PublicKey> arrayOfSigners = tx.getCommands().get(0).getSigners();
            Set<PublicKey> setOfSigners = new HashSet<>(arrayOfSigners);
            require.using("All participants must sign wRDIssuanceInit transaction",
                    setOfSigners.equals(listOfParticipantPublicKeys));

            return null;
        });
    }

    private void verifyWRDIssuance(LedgerTransaction tx) {
        requireThat(require -> {
            final List<wRDAccountState> inputs = tx.inputsOfType(wRDAccountState.class);
            final List<wRDAccountState> outputs = tx.outputsOfType(wRDAccountState.class);

            // 1. Two input states should be consumed
            require.using("Two input states should be consumed", inputs.size() == 2);

            // 2. Two output states should be created
            require.using("Two output states should be created", outputs.size() == 2);

            // Using similar pattern with verifyWRDCentralInit & verifyWRDIssuanceInit above we can determine
            // (source/source) and receiver wallet states by checking the owner is kdr or not.

            // But let's try different way (representative method A-B), this method will be used to other transactions.
            HashMap<String, HashMap<String, wRDAccountState>> inputOutputStatesMap = buildInputOutputMapWithAB(inputs
                    , outputs);

            // 3. Source's owner must be KDR and must be equal between the input and output state
            require.using("Source's owner in input state must be KDR",
                    isKDR(inputOutputStatesMap.get("INPUTS").get("SOURCE").getOwner()));
            require.using("Source's owner in input and output states must be equal",
                    inputOutputStatesMap.get("INPUTS").get("SOURCE").getOwner().equals(inputOutputStatesMap.get(
                            "OUTPUTS").get("SOURCE").getOwner()));

            // 4. Receiver's owner must not be KDR and must be equal between the input and output state
            require.using("Receiver's owner in input state must be not KDR",
                    !isKDR(inputOutputStatesMap.get("INPUTS").get("RECEIVER").getOwner()));
            require.using("Receiver's owner in input and output states must be equal",
                    inputOutputStatesMap.get("INPUTS").get("RECEIVER").getOwner().equals(inputOutputStatesMap.get(
                            "OUTPUTS").get("RECEIVER").getOwner()));

            // 5. Source issuer in output state must be equal to receiver owner in input state
            require.using("Source's issuer in output state must be equal to receiver's owner",
                    inputOutputStatesMap.get("OUTPUTS").get("SOURCE").getIssuer().equals(inputOutputStatesMap.get(
                            "INPUTS").get("RECEIVER").getOwner()));

            // 6. Receiver issuer in output state must be equal to source owner in input state
            require.using("Receiver's issuer in output state must be equal to source's owner",
                    inputOutputStatesMap.get("OUTPUTS").get("RECEIVER").getIssuer().equals(inputOutputStatesMap.get(
                            "INPUTS").get("SOURCE").getOwner()));

            Amount<Currency> transferAmount =
                    inputOutputStatesMap.get("INPUTS").get("SOURCE").getTokenBalance()
                            .minus(inputOutputStatesMap.get("OUTPUTS").get("SOURCE").getTokenBalance());

            // 7. Transfer amount must be greater than 0
            require.using("Transfer amount must be greater than 0", transferAmount.getQuantity() > 0);

            // 8. Source must have sufficient balance
            require.using("Source must have sufficient balance",
                    inputOutputStatesMap.get("INPUTS").get("SOURCE").getTokenBalance().getQuantity()
                            >= transferAmount.getQuantity());

            // 9. Token type must be IDR
            require.using("Source's token type in input state must be IDR",
                    inputOutputStatesMap.get("INPUTS").get("SOURCE").getTokenType().equals("IDR"));
            require.using("Receiver's token type in input state must be IDR",
                    inputOutputStatesMap.get("INPUTS").get("RECEIVER").getTokenType().equals("IDR"));
            require.using("Source's token type in output state must be IDR",
                    inputOutputStatesMap.get("OUTPUTS").get("SOURCE").getTokenType().equals("IDR"));
            require.using("Receiver's token type in output state must be IDR",
                    inputOutputStatesMap.get("OUTPUTS").get("RECEIVER").getTokenType().equals("IDR"));

            // 10. Version source in output state must be +1 of input state
            require.using("Version source in output state must be +1 of input state",
                    inputOutputStatesMap.get("OUTPUTS").get("SOURCE")
                            .getVersion() == inputOutputStatesMap.get("INPUTS").get("SOURCE").getVersion() + 1);

            // 11. Version receiver in output state must be +1 of input state
            require.using("Version receiver in output state must be +1 of input state",
                    inputOutputStatesMap.get("OUTPUTS").get("RECEIVER")
                            .getVersion() == inputOutputStatesMap.get("INPUTS").get("RECEIVER").getVersion() + 1);

            // 12. Last modified source in output state must be before now and after input state
            require.using("Last modified source in output state must be before now and after input state",
                    inputOutputStatesMap.get("OUTPUTS").get("SOURCE").getLastModified().isBefore(Instant.now())
                            && inputOutputStatesMap.get("OUTPUTS").get("SOURCE").getLastModified()
                            .isAfter(inputOutputStatesMap.get("INPUTS").get("SOURCE").getLastModified()));

            // 13. Last modified receiver in output state must be before now and after input state
            require.using("Last modified receiver in output state must be before now and after input state",
                    inputOutputStatesMap.get("OUTPUTS").get("RECEIVER").getLastModified().isBefore(Instant.now())
                            && inputOutputStatesMap.get("OUTPUTS").get("RECEIVER").getLastModified()
                            .isAfter(inputOutputStatesMap.get("INPUTS").get("RECEIVER").getLastModified()));

            // 14. All participants must sign wRDIssuance transaction
            Set<PublicKey> listOfParticipantPublicKeys =
                    inputOutputStatesMap.get("INPUTS").get("SOURCE").getParticipants().stream()
                            .map(AbstractParty::getOwningKey).collect(Collectors.toSet());
            listOfParticipantPublicKeys.addAll(inputOutputStatesMap.get("INPUTS").get("RECEIVER").getParticipants().stream()
                    .map(AbstractParty::getOwningKey).collect(Collectors.toSet()));
            List<PublicKey> arrayOfSigners = tx.getCommands().get(0).getSigners();
            Set<PublicKey> setOfSigners = new HashSet<>(arrayOfSigners);
            require.using("All participants must sign wRDIssuance transaction",
                    setOfSigners.equals(listOfParticipantPublicKeys));

            return null;
        });
    }

    private void verifyWRDTransfer(LedgerTransaction tx) {
        requireThat(require -> {
            final List<wRDAccountState> inputs = tx.inputsOfType(wRDAccountState.class);
            final List<wRDAccountState> outputs = tx.outputsOfType(wRDAccountState.class);

            // 1. Two input states should be consumed
            require.using("Two input states should be consumed", inputs.size() == 2);

            // 2. Two output states should be consumed
            require.using("Two output states should be created", outputs.size() == 2);

            HashMap<String, HashMap<String, wRDAccountState>> inputOutputStatesMap = buildInputOutputMapWithAB(inputs
                    , outputs);

            // 3. Source's owner must be non-KDR and must be equal between the input and output state
            require.using("Source's owner in input state must be non-KDR",
                    !isKDR(inputOutputStatesMap.get("INPUTS").get("SOURCE").getOwner()));
            require.using("Source's owner in input and output states must be equal",
                    inputOutputStatesMap.get("INPUTS").get("SOURCE").getOwner().equals(inputOutputStatesMap.get(
                            "OUTPUTS").get("SOURCE").getOwner()));

            // 4. Receiver's owner must be non-KDR and must be equal between the input and output state
            require.using("Receiver's owner in input state must be not KDR",
                    !isKDR(inputOutputStatesMap.get("INPUTS").get("RECEIVER").getOwner()));
            require.using("Receiver's owner in input and output states must be equal",
                    inputOutputStatesMap.get("INPUTS").get("RECEIVER").getOwner().equals(inputOutputStatesMap.get(
                            "OUTPUTS").get("RECEIVER").getOwner()));

            // 5. Receiver owner must be not equal with the source owner
            require.using("Receiver owner must be not equal with the source owner",
                    !inputOutputStatesMap.get("OUTPUTS").get("RECEIVER").getOwner().equals(inputOutputStatesMap.get(
                            "OUTPUTS").get("SOURCE").getOwner()));

            // 6. Source issuer in output state must be equal to receiver owner in input state
            require.using("Source's issuer in output state must be equal to receiver's owner",
                    inputOutputStatesMap.get("OUTPUTS").get("SOURCE").getIssuer().equals(inputOutputStatesMap.get(
                            "INPUTS").get("RECEIVER").getOwner()));

            // 7. Receiver issuer in output state must be equal to source owner in input state
            require.using("Receiver's issuer in output state must be equal to source's owner",
                    inputOutputStatesMap.get("OUTPUTS").get("RECEIVER").getIssuer().equals(inputOutputStatesMap.get(
                            "INPUTS").get("SOURCE").getOwner()));

            Amount<Currency> transferAmount =
                    inputOutputStatesMap.get("INPUTS").get("SOURCE").getTokenBalance()
                            .minus(inputOutputStatesMap.get("OUTPUTS").get("SOURCE").getTokenBalance());

            // 8. Transfer amount must be greater than 0
            require.using("Transfer amount must be greater than 0", transferAmount.getQuantity() > 0);

            // 9. Source must have sufficient balance
            require.using("Source must have sufficient balance",
                    inputOutputStatesMap.get("INPUTS").get("SOURCE").getTokenBalance().getQuantity()
                            >= transferAmount.getQuantity());

            // 10. Token type must be IDR
            require.using("Source's token type in input state must be IDR",
                    inputOutputStatesMap.get("INPUTS").get("SOURCE").getTokenType().equals("IDR"));
            require.using("Receiver's token type in input state must be IDR",
                    inputOutputStatesMap.get("INPUTS").get("RECEIVER").getTokenType().equals("IDR"));
            require.using("Source's token type in output state must be IDR",
                    inputOutputStatesMap.get("OUTPUTS").get("SOURCE").getTokenType().equals("IDR"));
            require.using("Receiver's token type in output state must be IDR",
                    inputOutputStatesMap.get("OUTPUTS").get("RECEIVER").getTokenType().equals("IDR"));

            // 11. Version source in output state must be +1 of input state
            require.using("Version source in output state must be +1 of input state",
                    inputOutputStatesMap.get("OUTPUTS").get("SOURCE")
                            .getVersion() == inputOutputStatesMap.get("INPUTS").get("SOURCE").getVersion() + 1);

            // 12. Version receiver in output state must be +1 of input state
            require.using("Version receiver in output state must be +1 of input state",
                    inputOutputStatesMap.get("OUTPUTS").get("RECEIVER")
                            .getVersion() == inputOutputStatesMap.get("INPUTS").get("RECEIVER").getVersion() + 1);

            // 13. Last modified source in output state must be before now and after input state
            require.using("Last modified source in output state must be before now and after input state",
                    inputOutputStatesMap.get("OUTPUTS").get("SOURCE").getLastModified().isBefore(Instant.now())
                            && inputOutputStatesMap.get("OUTPUTS").get("SOURCE").getLastModified()
                            .isAfter(inputOutputStatesMap.get("INPUTS").get("SOURCE").getLastModified()));

            // 14. Last modified receiver in output state must be before now and after input state
            require.using("Last modified receiver in output state must be before now and after input state",
                    inputOutputStatesMap.get("OUTPUTS").get("RECEIVER").getLastModified().isBefore(Instant.now())
                            && inputOutputStatesMap.get("OUTPUTS").get("RECEIVER").getLastModified()
                            .isAfter(inputOutputStatesMap.get("INPUTS").get("RECEIVER").getLastModified()));

            // 15. All participants must sign wRDTransfer transaction
            Set<PublicKey> listOfParticipantPublicKeys =
                    inputOutputStatesMap.get("INPUTS").get("SOURCE").getParticipants().stream()
                            .map(AbstractParty::getOwningKey).collect(Collectors.toSet());
            listOfParticipantPublicKeys.addAll(inputOutputStatesMap.get("INPUTS").get("RECEIVER").getParticipants().stream()
                    .map(AbstractParty::getOwningKey).collect(Collectors.toSet()));
            List<PublicKey> arrayOfSigners = tx.getCommands().get(0).getSigners();
            Set<PublicKey> setOfSigners = new HashSet<>(arrayOfSigners);
            require.using("All participants must sign wRDTransfer transaction",
                    setOfSigners.equals(listOfParticipantPublicKeys));

            return null;
        });
    }

    private void verifyWRDRedemption(LedgerTransaction tx) {
        requireThat(require -> {
            final List<wRDAccountState> inputs = tx.inputsOfType(wRDAccountState.class);
            final List<wRDAccountState> outputs = tx.outputsOfType(wRDAccountState.class);

            // 1. Two input states should be consumed
            require.using("Two input states should be consumed", inputs.size() == 2);

            // 2. Two output states should be consumed
            require.using("Two output states should be created", outputs.size() == 2);

            HashMap<String, HashMap<String, wRDAccountState>> inputOutputStatesMap = buildInputOutputMapWithAB(inputs
                    , outputs);

            // 3. Source's owner must be non-KDR and must be equal between the input and output state
            require.using("Source's owner in input state must be KDR",
                    !isKDR(inputOutputStatesMap.get("INPUTS").get("SOURCE").getOwner()));
            require.using("Source's owner in input and output states must be equal",
                    inputOutputStatesMap.get("INPUTS").get("SOURCE").getOwner().equals(inputOutputStatesMap.get(
                            "OUTPUTS").get("SOURCE").getOwner()));

            // 4. Receiver's owner must be KDR and must be equal between the input and output state
            require.using("Receiver's owner in input state must be not KDR",
                    isKDR(inputOutputStatesMap.get("INPUTS").get("RECEIVER").getOwner()));
            require.using("Receiver's owner in input and output states must be equal",
                    inputOutputStatesMap.get("INPUTS").get("RECEIVER").getOwner().equals(inputOutputStatesMap.get(
                            "OUTPUTS").get("RECEIVER").getOwner()));

            // 5. Receiver owner must be not equal with the source owner
            require.using("Receiver owner must be not equal with the source owner",
                    !inputOutputStatesMap.get("OUTPUTS").get("RECEIVER").getOwner().equals(inputOutputStatesMap.get(
                            "OUTPUTS").get("SOURCE").getOwner()));

            // 6. Source issuer in output state must be equal to receiver owner in input state
            require.using("Source's issuer in output state must be equal to receiver's owner",
                    inputOutputStatesMap.get("OUTPUTS").get("SOURCE").getIssuer().equals(inputOutputStatesMap.get(
                            "INPUTS").get("RECEIVER").getOwner()));

            // 7. Receiver issuer in output state must be equal to source owner in input state
            require.using("Receiver's issuer in output state must be equal to source's owner",
                    inputOutputStatesMap.get("OUTPUTS").get("RECEIVER").getIssuer().equals(inputOutputStatesMap.get(
                            "INPUTS").get("SOURCE").getOwner()));

            Amount<Currency> transferAmount =
                    inputOutputStatesMap.get("INPUTS").get("SOURCE").getTokenBalance()
                            .minus(inputOutputStatesMap.get("OUTPUTS").get("SOURCE").getTokenBalance());

            // 8. Transfer amount must be greater than 0
            require.using("Transfer amount must be greater than 0", transferAmount.getQuantity() > 0);

            // 9. Source must have sufficient balance
            require.using("Source must have sufficient balance",
                    inputOutputStatesMap.get("INPUTS").get("SOURCE").getTokenBalance().getQuantity()
                            >= transferAmount.getQuantity());

            // 10. Token type must be IDR
            require.using("Source's token type in input state must be IDR",
                    inputOutputStatesMap.get("INPUTS").get("SOURCE").getTokenType().equals("IDR"));
            require.using("Receiver's token type in input state must be IDR",
                    inputOutputStatesMap.get("INPUTS").get("RECEIVER").getTokenType().equals("IDR"));
            require.using("Source's token type in output state must be IDR",
                    inputOutputStatesMap.get("OUTPUTS").get("SOURCE").getTokenType().equals("IDR"));
            require.using("Receiver's token type in output state must be IDR",
                    inputOutputStatesMap.get("OUTPUTS").get("RECEIVER").getTokenType().equals("IDR"));

            // 11. Version source in output state must be +1 of input state
            require.using("Version source in output state must be +1 of input state",
                    inputOutputStatesMap.get("OUTPUTS").get("SOURCE")
                            .getVersion() == inputOutputStatesMap.get("INPUTS").get("SOURCE").getVersion() + 1);

            // 12. Version receiver in output state must be +1 of input state
            require.using("Version receiver in output state must be +1 of input state",
                    inputOutputStatesMap.get("OUTPUTS").get("RECEIVER")
                            .getVersion() == inputOutputStatesMap.get("INPUTS").get("RECEIVER").getVersion() + 1);

            // 13. Last modified source in output state must be before now and after input state
            require.using("Last modified source in output state must be before now and after input state",
                    inputOutputStatesMap.get("OUTPUTS").get("SOURCE").getLastModified().isBefore(Instant.now())
                            && inputOutputStatesMap.get("OUTPUTS").get("SOURCE").getLastModified()
                            .isAfter(inputOutputStatesMap.get("INPUTS").get("SOURCE").getLastModified()));

            // 14. Last modified receiver in output state must be before now and after input state
            require.using("Last modified receiver in output state must be before now and after input state",
                    inputOutputStatesMap.get("OUTPUTS").get("RECEIVER").getLastModified().isBefore(Instant.now())
                            && inputOutputStatesMap.get("OUTPUTS").get("RECEIVER").getLastModified()
                            .isAfter(inputOutputStatesMap.get("INPUTS").get("RECEIVER").getLastModified()));

            // 15. All participants must sign wRDRedemption transaction
            Set<PublicKey> listOfParticipantPublicKeys =
                    inputOutputStatesMap.get("INPUTS").get("SOURCE").getParticipants().stream()
                            .map(AbstractParty::getOwningKey).collect(Collectors.toSet());
            listOfParticipantPublicKeys.addAll(inputOutputStatesMap.get("INPUTS").get("RECEIVER").getParticipants().stream()
                    .map(AbstractParty::getOwningKey).collect(Collectors.toSet()));
            List<PublicKey> arrayOfSigners = tx.getCommands().get(0).getSigners();
            Set<PublicKey> setOfSigners = new HashSet<>(arrayOfSigners);
            require.using("All participants must sign wRDRedemption transaction",
                    setOfSigners.equals(listOfParticipantPublicKeys));

            return null;
        });
    }

    private void verifyWRD2RRDIssuanceInit(LedgerTransaction tx) {
        requireThat(require -> {
            final List<wRDAccountState> inputWRDs = tx.inputsOfType(wRDAccountState.class);
            final List<wRDAccountState> outputWRDs = tx.outputsOfType(wRDAccountState.class);
            final List<rRDAccountState> outputRRDs = tx.outputsOfType(rRDAccountState.class);

            // 1. One input wRDAccountState should be consumed
            require.using("One input wRDAccountState should be consumed", inputWRDs.size() == 1);

            // 2. One output wRDAccountState should be created
            require.using("One output wRDAccountState should be created", outputWRDs.size() == 1);

            // 3. One output rRDAccountState should be created
            require.using("One output rRDAccountState should be created", outputRRDs.size() == 1);

            wRDAccountState sourceInputWRD = inputWRDs.get(0);
            wRDAccountState sourceOutputWRD = outputWRDs.get(0);
            rRDAccountState receiverOutputRRD = outputRRDs.get(0);

            // 4. Wallet id of receiverOutputRRD must be not null and generated along with the external id
            require.using("Wallet id of receiverOutputRRD must be not null", receiverOutputRRD.getWalletId().getExternalId() != null);
            require.using(" Wallet id of receiverOutputRRD must be generated along with the external id",
                    receiverOutputRRD.getWalletId().getExternalId().equals(receiverOutputRRD.getOwnerExternalId()));

            // 5. Source's owner must be non KDR
            require.using("Source's owner in input wRDAccountState must be non KDR", !isKDR(sourceInputWRD.getOwner()));
            require.using("Source's owner in output wRDAccountState must be non KDR",
                    !isKDR(sourceOutputWRD.getOwner()));

            // 6. Receiver's owner (wholesaler) must be non KDR
            require.using("Receiver's owner (wholesaler) must be non KDR",
                    !isKDR(receiverOutputRRD.getOwnerWholesaler()));

            // 7. Source issuer in output wRDAccountState must be equal to receiver owner & issuer (wholesaler) in
            // output rRDAccountState
            require.using("Source issuer in output wRDAccountState must be equal to receiver owner (wholesaler) in " +
                            "output rRDAccountState",
                    sourceOutputWRD.getIssuer().equals(receiverOutputRRD.getOwnerWholesaler()));
            require.using("Source issuer in output wRDAccountState must be equal to receiver issuer (wholesaler) in " +
                            "output rRDAccountState",
                    sourceOutputWRD.getIssuer().equals(receiverOutputRRD.getIssuerWholesaler()));

            Amount<Currency> transferAmount = sourceInputWRD.getTokenBalance().minus(sourceOutputWRD.getTokenBalance());

            // 8. Transfer amount must be greater than 0
            require.using("Transfer amount must be greater than 0", transferAmount.getQuantity() > 0);

            // 9. Source must have sufficient balance (note: overlap with previous check)
            require.using("Source must have sufficient balance",
                    sourceInputWRD.getTokenBalance().getQuantity() >= transferAmount.getQuantity());

            // 10. Receiver token balance must be equal with transfer amount
            require.using("Receiver token balance must be equal with transfer amount",
                    receiverOutputRRD.getTokenBalance().getQuantity() == transferAmount.getQuantity());

            // 11. Token type must be IDR
            require.using("Source's wRD token type in input must be IDR", sourceInputWRD.getTokenType().equals("IDR"));
            require.using("Source's wRD token type in output must be IDR", sourceOutputWRD.getTokenType().equals("IDR"));
            require.using("Receiver's rRD token type in output must be IDR", receiverOutputRRD.getTokenType().equals(
                    "IDR"));

            // 12. Version source in output state must be +1 of source in input state
            require.using("Version source in output state must be +1 of source in input state",
                    sourceOutputWRD.getVersion() == sourceInputWRD.getVersion() + 1);

            // 13. Version receiver in output state must be greater than 0
            require.using("Version receiver in output state must be greater than 0", receiverOutputRRD.getVersion() > 0);

            // 14. Last modified source in output state must be before now and after source in input state
            require.using("Last modified source in output state must be before now and after source in input state",
                    sourceOutputWRD.getLastModified().isBefore(Instant.now()) &&
                            sourceOutputWRD.getLastModified().isAfter(sourceInputWRD.getLastModified()));

            // 15. Last Modified receiver in output state must be before now
            require.using("Last Modified receiver in output state must be before now",
                    receiverOutputRRD.getLastModified().isBefore(Instant.now()));

            // 16. All participants must sign wRD2rRDIssuanceInit transaction
            Set<PublicKey> listOfParticipantPublicKeys =
                    sourceInputWRD.getParticipants().stream()
                            .map(AbstractParty::getOwningKey).collect(Collectors.toSet());
            List<PublicKey> arrayOfSigners = tx.getCommands().get(0).getSigners();
            Set<PublicKey> setOfSigners = new HashSet<>(arrayOfSigners);
            require.using("All participants must sign wRD2rRDIssuanceInit transaction",
                    setOfSigners.equals(listOfParticipantPublicKeys));

            return null;
        });
    }

    private HashMap<String, HashMap<String, wRDAccountState>> buildInputOutputMapWithAB(List<wRDAccountState> inputs,
                                                                                        List<wRDAccountState> outputs) {
        // Common flow to determine which source and receiver party:
        // 1. Let A and B are two parties which one of them is source party and the other is receiver party.
        // 2. Define partyNamesAB = {A: PartyNameInput[0], B: PartyNameInput[1]}, ensure A != B.
        // 3. Define inputStates = {A: StateInput[0], B: StateInput[1]}
        // 4. Define outputStates and iterate outputs, check if PartyNameOutput equal to partyNamesAB[A] then put in
        // outputStates[partyNamesAB[A]], vice versa for case B. Ensure the outputStates key equal to 2 (which means
        // the key of outputStates equal to key of inputStates).
        // 5. Define diffAmountA = outputStates[A] - inputStates[A] and transferAmountB = outputStates[B] -
        // inputStates[B] and ensure diffAmountA == transferAmountB * 1.
        // 6. If diffAmountA < 0 then (sourceParty = A, receiverParty B) otherwise vice versa.
        // 7. Change A and B as Source or Receiver and wrap up the result as finalMap
        if (inputs.size() != 2 || outputs.size() != 2) {
            throw new IllegalArgumentException("Inputs and outputs must be consist of exact two states.");
        }
        // Step: 1-3
        final HashMap<String, String> partyNamesAB = new HashMap<>();
        final HashMap<String, wRDAccountState> inputStates = new HashMap<>();

        for (int i = 0; i < 2; i++) {
            String variable = (i == 0) ? "A" : "B";
            partyNamesAB.put(variable, inputs.get(i).getOwner().getName().toString());
            inputStates.put(variable, inputs.get(i));
        }

        // Step: 4
        final HashMap<String, wRDAccountState> outputStates = new HashMap<>();
        for (wRDAccountState state : outputs) {
            if (state.getOwner().getName().toString().equals(partyNamesAB.get("A"))) {
                outputStates.put("A", state);
            }
            if (state.getOwner().getName().toString().equals(partyNamesAB.get("B"))) {
                outputStates.put("B", state);
            }
        }
        if (outputStates.size() != 2) {
            // Since each A != B, if the length of keys of outputStates = 2 (which means include A and B) then
            // valid. Otherwise, invalid.
            throw new IllegalArgumentException("Inputs owner name must be equal to outputs owner name.");
        }

        // Step: 5
        // Note cannot use built-in amount minus() method, since the diff can be negative.
        final long diffAmountInputOutputStatesA =
                outputStates.get("A").getTokenBalance().getQuantity() - inputStates.get("A").getTokenBalance().getQuantity();
        final long diffAmountInputOutputStatesB =
                outputStates.get("B").getTokenBalance().getQuantity() - inputStates.get("B").getTokenBalance().getQuantity();
        if (diffAmountInputOutputStatesA != diffAmountInputOutputStatesB * -1) {
            throw new IllegalArgumentException("Diff amount states A not equal diff amount states B.");
        }

        // Step: 6
        String varSource = (diffAmountInputOutputStatesA < 0) ? "A" : "B";
        String varReceiver = (diffAmountInputOutputStatesB > 0) ? "B" : "A";

        // Step: 7
        inputStates.put("SOURCE", inputStates.get(varSource));
        inputStates.put("RECEIVER", inputStates.get(varReceiver));
        outputStates.put("SOURCE", outputStates.get(varSource));
        outputStates.put("RECEIVER", outputStates.get(varReceiver));
        // Clean up
        inputStates.remove(varSource);
        inputStates.remove(varReceiver);
        outputStates.remove(varSource);
        outputStates.remove(varReceiver);
        // Wrap up
        final HashMap<String, HashMap<String, wRDAccountState>> finalMap = new HashMap<>();
        finalMap.put("INPUTS", inputStates);
        finalMap.put("OUTPUTS", outputStates);
        return finalMap;
    }

    private boolean isKDR(@NotNull Party party) {
        return party.getName().getOrganisation().equals("KDR");
    }

    public interface Commands extends CommandData {
        class wRDCentralInitCommand implements Commands {
        }

        class wRDIssuanceInitCommand implements Commands {
        }

        class wRDIssuanceCommand implements Commands {
        }

        class wRDTransferCommand implements Commands {
        }

        class wRDRedemptionCommand implements Commands {
        }

        class wRD2rRDIssuanceInitCommand implements Commands {
        }
    }
}