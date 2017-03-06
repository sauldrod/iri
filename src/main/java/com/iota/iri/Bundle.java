package com.iota.iri;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import com.iota.iri.hash.Curl;
import com.iota.iri.hash.ISS;
import com.iota.iri.viewModel.Transaction;
import com.iota.iri.utils.Converter;

/**
 * A bundle is a group of transactions that follow each other from
 * currentIndex=0 to currentIndex=lastIndex
 * 
 * several bundles can form a single branch if they are chained.
 */
public class Bundle {

    private final List<List<Transaction>> transactions = new LinkedList<>();

    public Bundle(final byte[] bundle) {

        final Transaction bundleTransaction = Transaction.fromHash(bundle);
        if(bundleTransaction == null) {
            return;
        }
        final Map<byte[], Transaction> bundleTransactions = loadTransactionsFromTangle(bundleTransaction);
        
        for (Transaction transaction : bundleTransactions.values()) {

            if (transaction.getCurrentIndex() == 0 && transaction.getValidity() >= 0) {

                final List<Transaction> instanceTransactions = new LinkedList<>();

                final long lastIndex = transaction.getLastIndex();
                long bundleValue = 0;
                int i = 0;
            MAIN_LOOP:
                while (true) {

                    instanceTransactions.add(transaction);

                    if (transaction.getCurrentIndex() != i || transaction.getLastIndex() != lastIndex
                            || ((bundleValue += transaction.value()) < -Transaction.SUPPLY || bundleValue > Transaction.SUPPLY)) {
                        instanceTransactions.get(0).setValidity(-1, true);
                        break;
                    }

                    if (i++ == lastIndex) { // It's supposed to become -3812798742493 after 3812798742493 and to go "down" to -1 but we hope that noone will create such long bundles

                        if (bundleValue == 0) {

                            if (instanceTransactions.get(0).getValidity() == 0) {

                                final Curl bundleHash = new Curl();
                                for (final Transaction transaction2 : instanceTransactions) {
                                    bundleHash.absorb(transaction2.trits(), Transaction.ESSENCE_TRINARY_OFFSET, Transaction.ESSENCE_TRINARY_SIZE);
                                }
                                final int[] bundleHashTrits = new int[Transaction.BUNDLE_TRINARY_SIZE];
                                bundleHash.squeeze(bundleHashTrits, 0, bundleHashTrits.length);
                                if (Arrays.equals(Converter.bytes(bundleHashTrits, 0, Transaction.BUNDLE_TRINARY_SIZE), instanceTransactions.get(0).getBundleHash())) {

                                    final int[] normalizedBundle = ISS.normalizedBundle(bundleHashTrits);

                                    for (int j = 0; j < instanceTransactions.size(); ) {

                                        transaction = instanceTransactions.get(j);
                                        if (transaction.value() < 0) { // let's recreate the address of the transaction.

                                            final Curl address = new Curl();
                                            int offset = 0;
                                            do {

                                                address.absorb(
                                                        ISS.digest(Arrays.copyOfRange(normalizedBundle, offset, offset = (offset + ISS.NUMBER_OF_FRAGMENT_CHUNKS) % (Curl.HASH_LENGTH / Converter.NUMBER_OF_TRITS_IN_A_TRYTE)),
                                                        Arrays.copyOfRange(instanceTransactions.get(j).trits(), Transaction.SIGNATURE_MESSAGE_FRAGMENT_TRINARY_OFFSET, Transaction.SIGNATURE_MESSAGE_FRAGMENT_TRINARY_OFFSET + Transaction.SIGNATURE_MESSAGE_FRAGMENT_TRINARY_SIZE)),
                                                        0, Curl.HASH_LENGTH);

                                            } while (++j < instanceTransactions.size()
                                                    && Arrays.equals(instanceTransactions.get(j).getAddress(), transaction.getAddress())
                                                    && instanceTransactions.get(j).value() == 0);
                                            
                                            final int[] addressTrits = new int[Transaction.ADDRESS_TRINARY_SIZE];
                                            address.squeeze(addressTrits, 0, addressTrits.length);
                                            if (!Arrays.equals(Converter.bytes(addressTrits, 0, Transaction.ADDRESS_TRINARY_SIZE), transaction.getAddress())) {
                                                instanceTransactions.get(0).setValidity(-1, true);
                                                break MAIN_LOOP;
                                            }
                                        } else {
                                            j++;
                                        }
                                    }

                                    instanceTransactions.get(0).setValidity(1, true);
                                    transactions.add(instanceTransactions);
                                } else {
                                    instanceTransactions.get(0).setValidity(-1, true);
                                }
                            } else {
                                transactions.add(instanceTransactions);
                            }
                        } else {
                            instanceTransactions.get(0).setValidity(-1, true);
                        }
                        break;

                    } else {
                        transaction = bundleTransactions.get(transaction.trunkTransactionPointer);
                        if (transaction == null) {
                            break;
                        }
                    }
                }
            }
        }
    }


    private Map<byte[], Transaction> loadTransactionsFromTangle(final Transaction bundleTransaction) {
        final Map<byte[], Transaction> bundleTransactions = new HashMap<>();
        try {
            for (final Transaction transaction: bundleTransaction.getBundleTransactions()) {
                bundleTransactions.put(transaction.getHash(), transaction);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return bundleTransactions;
    }
    
    public List<List<Transaction>> getTransactions() {
        return transactions;
    }
}
