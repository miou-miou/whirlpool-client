package com.samourai.whirlpool.client.mix.handler;

import com.samourai.wallet.bip47.BIP47Util;
import com.samourai.wallet.bip47.rpc.BIP47Wallet;
import com.samourai.wallet.bip47.rpc.PaymentAddress;
import com.samourai.wallet.bip47.rpc.PaymentCode;
import com.samourai.wallet.segwit.SegwitAddress;
import com.samourai.whirlpool.client.utils.ClientUtils;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.lang.invoke.MethodHandles;

public class MixHandler implements IMixHandler {
    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public static final int BIP47_ACCOUNT_RECEIVE = Integer.MAX_VALUE;
    public static final int BIP47_ACCOUNT_COUNTERPARTY = Integer.MAX_VALUE - 1;

    private ECKey utxoKey;
    private BIP47Wallet bip47Wallet;
    private ECKey receiveKey;
    private int paymentCodeIndex;

    public MixHandler(ECKey utxoKey, BIP47Wallet bip47Wallet, int paymentCodeIndex) {
        this.utxoKey = utxoKey;
        this.bip47Wallet = bip47Wallet;
        this.receiveKey = null;
        this.paymentCodeIndex = paymentCodeIndex;
    }

    private PaymentCode computePaymentCodeCounterparty() throws Exception {
        PaymentCode paymentCodeCounter = new PaymentCode(this.bip47Wallet.getAccount(BIP47_ACCOUNT_COUNTERPARTY).getPaymentCode());
        if (!paymentCodeCounter.isValid()) {
            throw new Exception("Invalid paymentCode");
        }
        return paymentCodeCounter;
    }

    @Override
    public String computeReceiveAddress(NetworkParameters params) throws Exception {
        // compute receiveAddress with our own paymentCode counterparty
        PaymentCode paymentCodeCounter = computePaymentCodeCounterparty();
        PaymentAddress receiveAddress = BIP47Util.getInstance().getReceiveAddress(bip47Wallet, BIP47_ACCOUNT_RECEIVE, paymentCodeCounter, this.paymentCodeIndex, params);

        // bech32
        this.receiveKey = receiveAddress.getReceiveECKey();
        SegwitAddress addressToReceiver = new SegwitAddress(receiveKey, params);
        String bech32Address = addressToReceiver.getBech32AsString();
        if (log.isDebugEnabled()) {
            log.debug("receiveAddress=" + bech32Address + " (bip47AccountReceive=" + MixHandler.BIP47_ACCOUNT_RECEIVE + ", paymentCodeIndex=" + paymentCodeIndex + ")");
            log.debug("receiveECKey=" + this.receiveKey.getPrivateKeyAsWiF(params));
        }
        return bech32Address;
    }

    @Override
    public void signTransaction(Transaction tx, int inputIndex, long spendAmount, NetworkParameters params) throws Exception {
        ClientUtils.signSegwitInput(tx, inputIndex, utxoKey, spendAmount, params);
    }

    @Override
    public String signMessage(String message) {
        return utxoKey.signMessage(message);
    }

    @Override
    public byte[] getPubkey() {
        return utxoKey.getPubKey();
    }

    @Override
    public void postHttpRequest(String url, Object requestBody) throws Exception {
        // TODO use TOR
        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity result = restTemplate.postForEntity(url, requestBody, null);
            if (result == null || !result.getStatusCode().is2xxSuccessful()) {
                // response error
                throw new Exception("unable to registerOutput");
            }
        } catch(HttpServerErrorException e) {
            String restErrorMessage = ClientUtils.parseRestErrorMessage(e).orElse("unknown reason");
            throw new Exception("unable to registerOutput: " + restErrorMessage);
        }
    }

    @Override
    public IMixHandler computeMixHandlerForNextMix() {
        return new MixHandler(receiveKey, bip47Wallet, paymentCodeIndex);
    }
}
