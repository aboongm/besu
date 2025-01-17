/*
 * Copyright Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType.BLOCK_NOT_FOUND;
import static org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType.INTERNAL_ERROR;

import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonCallParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.TraceTypeParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.processor.TransactionTrace;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.JsonRpcErrorResponse;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.transaction.TransactionSimulator;
import org.hyperledger.besu.ethereum.vm.DebugOperationTracer;

import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TraceCall extends AbstractTraceByBlock implements JsonRpcMethod {
  private static final Logger LOG = LoggerFactory.getLogger(TraceCall.class);

  public TraceCall(
      final BlockchainQueries blockchainQueries,
      final ProtocolSchedule protocolSchedule,
      final TransactionSimulator transactionSimulator) {
    super(blockchainQueries, protocolSchedule, transactionSimulator);
  }

  @Override
  public String getName() {
    return transactionSimulator != null ? RpcMethod.TRACE_CALL.getMethodName() : null;
  }

  @Override
  protected Object resultByBlockNumber(
      final JsonRpcRequestContext requestContext, final long blockNumber) {
    final JsonCallParameter callParams =
        JsonCallParameterUtil.validateAndGetCallParams(requestContext);
    final TraceTypeParameter traceTypeParameter =
        requestContext.getRequiredParameter(1, TraceTypeParameter.class);
    final String blockNumberString = String.valueOf(blockNumber);
    LOG.atTrace()
        .setMessage("Received RPC rpcName={} callParams={} block={} traceTypes={}")
        .addArgument(this::getName)
        .addArgument(callParams)
        .addArgument(blockNumberString)
        .addArgument(traceTypeParameter)
        .log();

    final Optional<BlockHeader> maybeBlockHeader =
        blockchainQueriesSupplier.get().getBlockHeaderByNumber(blockNumber);

    if (maybeBlockHeader.isEmpty()) {
      return new JsonRpcErrorResponse(requestContext.getRequest().getId(), BLOCK_NOT_FOUND);
    }

    final Set<TraceTypeParameter.TraceType> traceTypes = traceTypeParameter.getTraceTypes();

    final DebugOperationTracer tracer = new DebugOperationTracer(buildTraceOptions(traceTypes));
    return transactionSimulator
        .process(
            callParams,
            buildTransactionValidationParams(),
            tracer,
            (mutableWorldState, maybeSimulatorResult) ->
                maybeSimulatorResult.map(
                    result -> {
                      if (result.isInvalid()) {
                        LOG.error(String.format("Invalid simulator result %s", result));
                        return new JsonRpcErrorResponse(
                            requestContext.getRequest().getId(), INTERNAL_ERROR);
                      }

                      final TransactionTrace transactionTrace =
                          new TransactionTrace(
                              result.getTransaction(), result.getResult(), tracer.getTraceFrames());

                      final Block block =
                          blockchainQueriesSupplier.get().getBlockchain().getChainHeadBlock();

                      return getTraceCallResult(
                          protocolSchedule, traceTypes, result, transactionTrace, block);
                    }),
            maybeBlockHeader.get())
        .orElse(new JsonRpcErrorResponse(requestContext.getRequest().getId(), INTERNAL_ERROR));
  }
}
