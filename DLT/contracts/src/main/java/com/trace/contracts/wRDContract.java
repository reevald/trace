package com.trace.contracts;

import com.trace.states.wRDAccountState;
import net.corda.core.contracts.*;
import net.corda.core.identity.Party;
import net.corda.core.transactions.LedgerTransaction;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Currency;
import java.util.List;

import static net.corda.core.contracts.ContractsDSL.requireThat;

public class wRDContract implements Contract {
    public static final String ID = "com.trace.contracts.wRDContract";

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
        } else {
            throw new IllegalArgumentException("Unrecognised command");
        }
    }

    private void verifyWRDCentralInit(LedgerTransaction tx) {
        requireThat(require -> {
            require.using("No input states should be consumed", tx.getInputs().isEmpty());
            require.using("Only one output state should be created", tx.getOutputs().size() == 1);

            final wRDAccountState output = tx.outputsOfType(wRDAccountState.class).get(0);

            require.using("Owner and issuer must be the same (KDR)", output.getOwner().equals(output.getIssuer()));
            require.using("Owner must be KDR", isKDR(output.getOwner()));
            require.using("Token balance must be greater than 0", output.getTokenBalance().getQuantity() > 0);
            require.using("Token type must be IDR", output.getTokenType().equals("IDR"));
            require.using("Version must be greater than 0", output.getVersion() > 0);
            require.using("Last Modified must be before now", output.getLastModified().isBefore(Instant.now()));

            return null;
        });
    }

    private void verifyWRDIssuanceInit(LedgerTransaction tx) {
        requireThat(require -> {
            require.using("One input state should be consumed", tx.getInputs().size() == 1);
            require.using("Two output states should be created", tx.getOutputs().size() == 2);

            final wRDAccountState input = tx.inputsOfType(wRDAccountState.class).get(0);
            final List<wRDAccountState> outputs = tx.outputsOfType(wRDAccountState.class);

            require.using("Sender's owner must be KDR", isKDR(input.getOwner()));

            wRDAccountState senderOutput = null;
            wRDAccountState receiverOutput = null;

            for (wRDAccountState output : outputs) {
                if (isKDR(output.getOwner())) {
                    senderOutput = output;
                } else {
                    receiverOutput = output;
                }
            }

            require.using("Must have KDR output", senderOutput != null);
            require.using("Must have non-KDR output", receiverOutput != null);
            require.using("Receiver's owner must not be KDR", !isKDR(receiverOutput.getOwner()));

            Amount<Currency> transferAmount = input.getTokenBalance().minus(senderOutput.getTokenBalance());
            require.using("Transfer amount must be greater than 0", transferAmount.getQuantity() > 0);
            require.using("Sender must have sufficient balance",
                    input.getTokenBalance().getQuantity() >= transferAmount.getQuantity());
            require.using("Token type must be IDR", input.getTokenType().equals("IDR"));

            require.using("Version senderOutput must be +1 of input.",
                    senderOutput.getVersion() == input.getVersion() + 1);
            require.using("Version receiverOutput must be greater than 0.", receiverOutput.getVersion() > 0);

            require.using("Last modified senderOutput must be before now and after input.",
                    senderOutput.getLastModified().isBefore(Instant.now()) &&
                            senderOutput.getLastModified().isAfter(input.getLastModified()));
            require.using("Last Modified receiverOutput must be before now",
                    receiverOutput.getLastModified().isBefore(Instant.now()));

            return null;
        });
    }

    private boolean isKDR(Party party) {
        return party.getName().getOrganisation().equals("KDR");
    }

    public interface Commands extends CommandData {
        class wRDCentralInitCommand implements Commands {
        }

        class wRDIssuanceInitCommand implements Commands {
        }
    }
}