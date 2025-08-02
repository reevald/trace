package com.trace.contracts;

import com.trace.states.rRDAccountState;
import com.trace.states.wRDAccountState;
import net.corda.core.contracts.*;
import net.corda.core.transactions.LedgerTransaction;

import java.util.Currency;
import java.util.List;

import static net.corda.core.contracts.ContractsDSL.requireThat;

public class rRDContract implements Contract {
    public static final String ID = "com.trace.contracts.rRDContract";

    @Override
    public void verify(LedgerTransaction tx) {
        if (tx.getCommands().isEmpty()) {
            throw new IllegalArgumentException("No commands found");
        }
        final CommandWithParties<CommandData> command = tx.getCommands().get(0);
        final CommandData commandData = command.getValue();

        if (commandData instanceof Commands.wRD2rRDIssuanceInitCommand) {
            verifyWRD2rRDIssuanceInit(tx);
        } else {
            throw new IllegalArgumentException("Unrecognised command");
        }
    }

    private void verifyWRD2rRDIssuanceInit(LedgerTransaction tx) {
        requireThat(require -> {
            return null;
        });
    }

    private boolean isKDR(net.corda.core.identity.Party party) {
        return party.getName().getOrganisation().equals("KDR");
    }

    public interface Commands extends CommandData {
        class wRD2rRDIssuanceInitCommand implements Commands {}
    }
}