package com.samourai.whirlpool.client.wallet.orchestrator;

import com.samourai.whirlpool.client.wallet.WhirlpoolAccount;
import com.samourai.whirlpool.client.wallet.WhirlpoolWallet;
import com.samourai.whirlpool.client.wallet.beans.WhirlpoolUtxo;
import com.samourai.whirlpool.client.wallet.beans.WhirlpoolUtxoStatus;
import com.samourai.whirlpool.client.whirlpool.beans.Pool;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoMixOrchestrator extends AbstractOrchestrator {
  private final Logger log = LoggerFactory.getLogger(AutoMixOrchestrator.class);
  private WhirlpoolWallet whirlpoolWallet;

  public AutoMixOrchestrator(WhirlpoolWallet whirlpoolWallet, int loopDelay) {
    super(loopDelay);
    this.whirlpoolWallet = whirlpoolWallet;
  }

  @Override
  protected void resetOrchestrator() {
    super.resetOrchestrator();
  }

  @Override
  protected void runOrchestrator() {
    try {
      resumePremix();
    } catch (Exception e) {
      log.error("", e);
    }
  }

  protected void resumePremix() throws Exception {
    if (log.isDebugEnabled()) {
      log.debug("Checking for PREMIX utxos ready to mix...");
    }
    // rescan premix
    whirlpoolWallet.getUtxosPremix(true);
  }

  @Override
  public synchronized void start() {
    super.start();
  }

  @Override
  public synchronized void stop() {
    super.stop();
  }

  public void onUtxoDetected(WhirlpoolUtxo whirlpoolUtxo) {
    try {
      if (WhirlpoolAccount.PREMIX.equals(whirlpoolUtxo.getAccount())
          && WhirlpoolUtxoStatus.READY.equals(whirlpoolUtxo.getStatus())) {

        // assign pool if not already assigned
        if (whirlpoolUtxo.getPool() == null) {
          Collection<Pool> pools = whirlpoolWallet.getPools().findForPremix(whirlpoolUtxo);
          if (pools.isEmpty()) {
            log.warn("No pool for this denomination: " + whirlpoolUtxo.toString());
            whirlpoolUtxo.setError("No pool for this denomination");
            return;
          }

          // assign pool from biggest denomination possible
          whirlpoolUtxo.setPool(pools.iterator().next());
        }

        if (log.isDebugEnabled()) {
          log.debug(" o AutoMix: new utxo detected, adding to mixQueue: " + whirlpoolUtxo);
        }
        whirlpoolWallet.mixQueue(whirlpoolUtxo);
      } else {
        if (log.isDebugEnabled()) {
          log.debug(" o AutoMix: new utxo detected, NOT adding to mixQueue: " + whirlpoolUtxo);
        }
      }
    } catch (Exception e) {
      log.error("", e);
    }
  }
}