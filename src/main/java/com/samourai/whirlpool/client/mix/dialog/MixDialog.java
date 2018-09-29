package com.samourai.whirlpool.client.mix.dialog;

import com.samourai.http.client.HttpException;
import com.samourai.stomp.client.StompTransport;
import com.samourai.whirlpool.client.exception.NotifiableException;
import com.samourai.whirlpool.client.utils.ClientUtils;
import com.samourai.whirlpool.client.whirlpool.WhirlpoolClientConfig;
import com.samourai.whirlpool.protocol.WhirlpoolProtocol;
import com.samourai.whirlpool.protocol.websocket.MixMessage;
import com.samourai.whirlpool.protocol.websocket.messages.*;
import com.samourai.whirlpool.protocol.websocket.notifications.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.HashSet;
import java.util.Set;

public class MixDialog {
    // non-static logger to prefix it with stomp sessionId
    private Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private MixDialogListener listener;
    private StompTransport transport;
    private WhirlpoolClientConfig clientConfig;

    // mix data
    private String mixId;
    private MixStatus mixStatus;
    private boolean gotConfirmInputResponse; // will get it after CONFIRM_INPUT
    private RegisterOutputMixStatusNotification earlyRegisterOutputMixStatusNotification; // we may get early REGISTER_OUTPUT notification (before registerInputResponse)

    // computed values
    private Set<MixStatus> mixStatusCompleted = new HashSet<>();
    private boolean done;

    public MixDialog(MixDialogListener listener, StompTransport transport, WhirlpoolClientConfig clientConfig) {
        this.listener = listener;
        this.clientConfig = clientConfig;
        this.transport = transport;
    }

    public void setLogPrefix(String logPrefix) {
        this.log = ClientUtils.prefixLogger(log, logPrefix);
    }

    public synchronized void onPrivateReceived(MixMessage mixMessage) {
        if (done) {
            log.info("Ignoring mixMessage (done)");
            return;
        }
        try {
            Class payloadClass = mixMessage.getClass();
            if (ErrorResponse.class.isAssignableFrom(payloadClass)) {
                String errorMessage = ((ErrorResponse)mixMessage).message;
                exitOnResponseError(errorMessage);
            } else {
                if (MixStatusNotification.class.isAssignableFrom(mixMessage.getClass())) {
                    onMixStatusNotificationChange((MixStatusNotification)mixMessage);
                }
                else if (ConfirmInputResponse.class.isAssignableFrom(payloadClass)) {
                    this.gotConfirmInputResponse = true;
                    listener.onConfirmInputResponse((ConfirmInputResponse) mixMessage);

                    // if we received early REGISTER_OUTPUT notification, register ouput now
                    if (earlyRegisterOutputMixStatusNotification != null) {
                        doRegisterOutput(earlyRegisterOutputMixStatusNotification);
                    }
                }
                else {
                    log.error("Unexpected mixMessage, registeredInput=true");
                }
            }
        }
        catch(NotifiableException e) {
            log.error("onPrivateReceived NotifiableException: " + e.getMessage());
            exitOnResponseError(e.getMessage());
        }
        catch(Exception e) {
            log.error("onPrivateReceived Exception", e);
            exitOnProtocolError(e);
        }
    }

    private void exitOnProtocolError(Exception e) {
        log.error("Protocol error", e);
        listener.exitOnProtocolError();
        done = true;
    }

    private void exitOnResponseError(String error) {
        listener.exitOnResponseError(error);
        done = true;
    }

    private void onMixStatusNotificationChange(MixStatusNotification notification) throws Exception {
        if (mixId == null) {
            mixId = notification.mixId;
        } else if (!notification.mixId.equals(mixId)) {
            log.error("Invalid mixId: expected=" + mixId + ", actual=" + notification.mixId);
            throw new Exception("Invalid mixId");
        }

        // check status chronology
        if (mixStatusCompleted.contains(notification.status)) {
            throw new Exception("mixStatus already completed: " + notification.status);
        }
        if (mixStatus != null && notification.status.equals(mixStatus)) {
            throw new Exception("Duplicate mixStatusNotification: " + mixStatus);
        }
        this.mixStatus = notification.status;

        if (MixStatus.FAIL.equals(notification.status)) {
            done = true;
            listener.onFail();
            return;
        }

        if (MixStatus.CONFIRM_INPUT.equals(notification.status)) {
            ConfirmInputMixStatusNotification confirmInputMixStatusNotification = (ConfirmInputMixStatusNotification) notification;

            ConfirmInputRequest confirmInputRequest = listener.confirmInput(confirmInputMixStatusNotification);
            transport.send(WhirlpoolProtocol.ENDPOINT_CONFIRM_INPUT, confirmInputRequest);
            mixStatusCompleted.add(MixStatus.CONFIRM_INPUT);
        } else if (mixStatusCompleted.contains(MixStatus.CONFIRM_INPUT)) {
            if (gotConfirmInputResponse()) {

                if (MixStatus.REGISTER_OUTPUT.equals(notification.status)) {
                    doRegisterOutput((RegisterOutputMixStatusNotification)notification);
                    mixStatusCompleted.add(MixStatus.REGISTER_OUTPUT);

                } else if (mixStatusCompleted.contains(MixStatus.REGISTER_OUTPUT)) {

                    // don't reveal output if already signed
                    if (!mixStatusCompleted.contains(MixStatus.SIGNING) && MixStatus.REVEAL_OUTPUT.equals(notification.status)) {
                        RevealOutputRequest revealOutputRequest = listener.revealOutput((RevealOutputMixStatusNotification) notification);
                        transport.send(WhirlpoolProtocol.ENDPOINT_REVEAL_OUTPUT, revealOutputRequest);
                        mixStatusCompleted.add(MixStatus.REVEAL_OUTPUT);

                    } else if (!mixStatusCompleted.contains(MixStatus.REVEAL_OUTPUT)) { // don't sign or success if output was revealed

                        if (MixStatus.SIGNING.equals(notification.status)) {
                            SigningRequest signingRequest = listener.signing((SigningMixStatusNotification) notification);
                            transport.send(WhirlpoolProtocol.ENDPOINT_SIGNING, signingRequest);
                            mixStatusCompleted.add(MixStatus.SIGNING);

                        } else if (mixStatusCompleted.contains(MixStatus.SIGNING)) {

                            if (MixStatus.SUCCESS.equals(notification.status)) {
                                listener.onSuccess();
                                done = true;
                                return;
                            }
                        } else {
                            log.warn(" x SIGNING not completed");
                            if (log.isDebugEnabled()) {
                                log.error("Ignoring mixStatusNotification: " + ClientUtils.toJsonString(notification));
                            }
                        }
                    } else {
                        log.warn(" x REVEAL_OUTPUT already completed");
                        if (log.isDebugEnabled()) {
                            log.error("Ignoring mixStatusNotification: " + ClientUtils.toJsonString(notification));
                        }
                    }
                } else {
                    log.warn(" x REGISTER_OUTPUT not completed");
                    if (log.isDebugEnabled()) {
                        log.error("Ignoring mixStatusNotification: " + ClientUtils.toJsonString(notification));
                    }
                }
            } else {
                if (MixStatus.REGISTER_OUTPUT.equals(notification.status)) {
                    // early REGISTER_OUTPUT notification (before RegisterInputResponse).
                    // keep it to register output as soon as we receive RegisterInputResponse
                    this.earlyRegisterOutputMixStatusNotification = (RegisterOutputMixStatusNotification)notification;
                }

                log.info(" > Trying to join current mix...");
            }
        } else {
            log.info(" > Waiting for next mix...");
            if (log.isDebugEnabled()) {
                log.debug("Current mix status: " + notification.status);
            }
        }
    }

    private void doRegisterOutput(RegisterOutputMixStatusNotification registerOutputMixStatusNotification) throws Exception {
        try {
            String registerOutputUrl = WhirlpoolProtocol.computeRegisterOutputUrl(clientConfig.getServer());
            listener.postRegisterOutput(registerOutputMixStatusNotification, registerOutputUrl);
        } catch(HttpException e) {
            String restErrorResponseMessage = ClientUtils.parseRestErrorMessage(e);
            if (restErrorResponseMessage != null) {
                throw new NotifiableException(restErrorResponseMessage);
            }
            throw e;
        }
    }

    protected boolean gotConfirmInputResponse() {
        return gotConfirmInputResponse;
    }

    public String getMixId() {
        return mixId;
    }
}