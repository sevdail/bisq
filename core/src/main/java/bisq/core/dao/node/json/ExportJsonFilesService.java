/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.dao.node.json;

import bisq.core.dao.DaoOptionKeys;
import bisq.core.dao.DaoSetupService;
import bisq.core.dao.state.BsqState;
import bisq.core.dao.state.BsqStateService;
import bisq.core.dao.state.blockchain.Block;
import bisq.core.dao.state.blockchain.PubKeyScript;
import bisq.core.dao.state.blockchain.Tx;
import bisq.core.dao.state.blockchain.TxOutput;
import bisq.core.dao.state.blockchain.TxType;

import bisq.common.storage.FileUtil;
import bisq.common.storage.JsonFileManager;
import bisq.common.storage.Storage;
import bisq.common.util.Utilities;

import org.bitcoinj.core.Utils;

import com.google.inject.Inject;

import javax.inject.Named;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.nio.file.Paths;

import java.io.File;
import java.io.IOException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;

@Slf4j
public class ExportJsonFilesService implements DaoSetupService {
    private final BsqStateService bsqStateService;
    private final File storageDir;
    private final boolean dumpBlockchainData;

    private final ListeningExecutorService executor = Utilities.getListeningExecutorService("JsonExporter",
            1, 1, 1200);
    private JsonFileManager txFileManager, txOutputFileManager, bsqStateFileManager;

    @Inject
    public ExportJsonFilesService(BsqStateService bsqStateService,
                                  @Named(Storage.STORAGE_DIR) File storageDir,
                                  @Named(DaoOptionKeys.DUMP_BLOCKCHAIN_DATA) boolean dumpBlockchainData) {
        this.bsqStateService = bsqStateService;
        this.storageDir = storageDir;
        this.dumpBlockchainData = dumpBlockchainData;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DaoSetupService
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void addListeners() {
    }

    @Override
    public void start() {
        if (dumpBlockchainData) {
            File jsonDir = new File(Paths.get(storageDir.getAbsolutePath(), "json").toString());
            File txDir = new File(Paths.get(storageDir.getAbsolutePath(), "json", "tx").toString());
            File txOutputDir = new File(Paths.get(storageDir.getAbsolutePath(), "json", "txo").toString());
            File bsqStateDir = new File(Paths.get(storageDir.getAbsolutePath(), "json", "all").toString());
            try {
                if (txDir.exists())
                    FileUtil.deleteDirectory(txDir);
                if (txOutputDir.exists())
                    FileUtil.deleteDirectory(txOutputDir);
                if (bsqStateDir.exists())
                    FileUtil.deleteDirectory(bsqStateDir);
                if (jsonDir.exists())
                    FileUtil.deleteDirectory(jsonDir);
            } catch (IOException e) {
                log.error(e.toString());
                e.printStackTrace();
            }

            if (!jsonDir.mkdir())
                log.warn("make jsonDir failed.\njsonDir=" + jsonDir.getAbsolutePath());

            if (!txDir.mkdir())
                log.warn("make txDir failed.\ntxDir=" + txDir.getAbsolutePath());

            if (!txOutputDir.mkdir())
                log.warn("make txOutputDir failed.\ntxOutputDir=" + txOutputDir.getAbsolutePath());

            if (!bsqStateDir.mkdir())
                log.warn("make bsqStateDir failed.\nbsqStateDir=" + bsqStateDir.getAbsolutePath());

            txFileManager = new JsonFileManager(txDir);
            txOutputFileManager = new JsonFileManager(txOutputDir);
            bsqStateFileManager = new JsonFileManager(bsqStateDir);
        }
    }

    public void shutDown() {
        if (dumpBlockchainData) {
            txFileManager.shutDown();
            txOutputFileManager.shutDown();
            bsqStateFileManager.shutDown();
        }
    }

    public void exportToJson() {
        if (dumpBlockchainData) {
            // We store the data we need once we write the data to disk (in the thread) locally.
            // Access to bsqStateService is single threaded, we must not access bsqStateService from the thread.
            List<JsonTxOutput> allJsonTxOutputs = new ArrayList<>();

            List<JsonTx> jsonTxs = bsqStateService.getTxStream()
                    .map(tx -> {
                        JsonTx jsonTx = getJsonTx(tx);
                        allJsonTxOutputs.addAll(jsonTx.getOutputs());
                        return jsonTx;
                    }).collect(Collectors.toList());

            BsqState bsqState = bsqStateService.getClone();
            List<JsonBlock> jsonBlockList = bsqState.getBlocks().stream()
                    .map(this::getJsonBlock)
                    .collect(Collectors.toList());
            JsonBlocks jsonBlocks = new JsonBlocks(bsqState.getChainHeight(), jsonBlockList);

            ListenableFuture<Void> future = executor.submit(() -> {
                bsqStateFileManager.writeToDisc(Utilities.objectToJson(jsonBlocks), "blocks");
                allJsonTxOutputs.forEach(jsonTxOutput -> txOutputFileManager.writeToDisc(Utilities.objectToJson(jsonTxOutput), jsonTxOutput.getId()));
                jsonTxs.forEach(jsonTx -> txFileManager.writeToDisc(Utilities.objectToJson(jsonTx), jsonTx.getId()));
                return null;
            });

            Futures.addCallback(future, new FutureCallback<>() {
                public void onSuccess(Void ignore) {
                    log.trace("onSuccess");
                }

                public void onFailure(@NotNull Throwable throwable) {
                    log.error(throwable.toString());
                    throwable.printStackTrace();
                }
            });
        }
    }

    private JsonBlock getJsonBlock(Block block) {
        List<JsonTx> jsonTxs = block.getTxs().stream()
                .map(this::getJsonTx)
                .collect(Collectors.toList());
        return new JsonBlock(block.getHeight(),
                block.getTime(),
                block.getHash(),
                block.getPreviousBlockHash(),
                jsonTxs);
    }

    private JsonTx getJsonTx(Tx tx) {
        JsonTxType jsonTxType = getJsonTxType(tx);
        String jsonTxTypeDisplayString = getJsonTxTypeDisplayString(jsonTxType);
        return new JsonTx(tx.getId(),
                tx.getBlockHeight(),
                tx.getBlockHash(),
                tx.getTime(),
                getJsonTxInputs(tx),
                getJsonTxOutputs(tx),
                jsonTxType,
                jsonTxTypeDisplayString,
                bsqStateService.getBurntFee(tx.getId()),
                tx.getUnlockBlockHeight());
    }

    private List<JsonTxInput> getJsonTxInputs(Tx tx) {
        return tx.getTxInputs().stream()
                .map(txInput -> {
                    Optional<TxOutput> optionalTxOutput = bsqStateService.getConnectedTxOutput(txInput);
                    if (optionalTxOutput.isPresent()) {
                        TxOutput connectedTxOutput = optionalTxOutput.get();
                        boolean isBsqTxOutputType = bsqStateService.isBsqTxOutputType(connectedTxOutput);
                        return new JsonTxInput(txInput.getConnectedTxOutputIndex(),
                                txInput.getConnectedTxOutputTxId(),
                                connectedTxOutput.getValue(),
                                isBsqTxOutputType,
                                connectedTxOutput.getAddress(),
                                tx.getTime());
                    } else {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<JsonTxOutput> getJsonTxOutputs(Tx tx) {
        JsonTxType jsonTxType = getJsonTxType(tx);
        String jsonTxTypeDisplayString = getJsonTxTypeDisplayString(jsonTxType);
        return tx.getTxOutputs().stream()
                .map(txOutput -> {
                    boolean isBsqTxOutputType = bsqStateService.isBsqTxOutputType(txOutput);
                    long bsqAmount = isBsqTxOutputType ? txOutput.getValue() : 0;
                    long btcAmount = !isBsqTxOutputType ? txOutput.getValue() : 0;
                    PubKeyScript pubKeyScript = txOutput.getPubKeyScript();
                    JsonScriptPubKey scriptPubKey = pubKeyScript != null ? new JsonScriptPubKey(pubKeyScript) : null;
                    JsonSpentInfo spentInfo = bsqStateService.getSpentInfo(txOutput).map(JsonSpentInfo::new).orElse(null);
                    JsonTxOutputType txOutputType = JsonTxOutputType.valueOf(txOutput.getTxOutputType().name());
                    int lockTime = txOutput.getLockTime();
                    String opReturn = txOutput.getOpReturnData() != null ? Utils.HEX.encode(txOutput.getOpReturnData()) : null;
                    boolean isUnspent = bsqStateService.isUnspent(txOutput.getKey());
                    return new JsonTxOutput(tx.getId(),
                            txOutput.getIndex(),
                            bsqAmount,
                            btcAmount,
                            tx.getBlockHeight(),
                            isBsqTxOutputType,
                            bsqStateService.getBurntFee(tx.getId()),
                            txOutput.getAddress(),
                            scriptPubKey,
                            spentInfo,
                            tx.getTime(),
                            jsonTxType,
                            jsonTxTypeDisplayString,
                            txOutputType,
                            txOutputType.getDisplayString(),
                            opReturn,
                            lockTime,
                            isUnspent
                    );
                })
                .collect(Collectors.toList());
    }

    private String getJsonTxTypeDisplayString(JsonTxType jsonTxType) {
        return jsonTxType != null ? jsonTxType.getDisplayString() : "";
    }

    private JsonTxType getJsonTxType(Tx tx) {
        TxType txType = tx.getTxType();
        return txType != null ? JsonTxType.valueOf(txType.name()) : null;
    }
}