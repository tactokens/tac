package com.tacplatform.it.sync.activation

import com.typesafe.config.Config
import com.tacplatform.features.BlockchainFeatureStatus
import com.tacplatform.features.api.{FeatureActivationStatus, NodeFeatureStatus}
import com.tacplatform.it.api.SyncHttpApi._
import com.tacplatform.it.api.BlockHeader
import com.tacplatform.it.transactions.NodesFromDocker
import com.tacplatform.it.{NodeConfigs, ReportingTestName}
import org.scalatest.{CancelAfterFailure, FreeSpec, Matchers}

class NotActivateFeatureTestSuite
    extends FreeSpec
    with Matchers
    with CancelAfterFailure
    with ActivationStatusRequest
    with ReportingTestName
    with NodesFromDocker {

  private val votingInterval             = 14
  private val blocksForActivation        = 14
  private val votingFeatureNum: Short    = 1
  private val nonVotingFeatureNum: Short = 2

  override protected def nodeConfigs: Seq[Config] =
    NodeConfigs.newBuilder
      .overrideBase(
        _.raw(
          s"""tac {
         |  blockchain {
         |    custom {
         |      functionality {
         |        pre-activated-features = {}
         |        feature-check-blocks-period = $votingInterval
         |        blocks-for-feature-activation = $blocksForActivation
         |      }
         |    }
         |  }
         |  features.supported=[$nonVotingFeatureNum]
         |  miner.quorum = 1
         |}""".stripMargin
        ))
      .withDefault(2)
      .buildNonConflicting()

  private var activationStatusInfoBefore = Seq.empty[FeatureActivationStatus]
  private var activationStatusInfoAfter  = Seq.empty[FeatureActivationStatus]

  "get activation status info" in {
    nodes.waitForHeight(votingInterval - 1)
    activationStatusInfoBefore = nodes.map(_.featureActivationStatus(votingFeatureNum))
    nodes.waitForHeight(votingInterval + 1)
    activationStatusInfoAfter = nodes.map(_.featureActivationStatus(votingFeatureNum))
  }

  "supported blocks is not increased when nobody votes for feature" in {
    val generatedBlocks: Seq[BlockHeader] = nodes.head.blockHeadersSeq(1, votingInterval - 1)
    val featuresMapInGeneratedBlocks       = generatedBlocks.flatMap(b => b.features.getOrElse(Seq.empty)).groupBy(x => x)
    val votesForFeature1                   = featuresMapInGeneratedBlocks.getOrElse(votingFeatureNum, Seq.empty).length

    votesForFeature1 shouldBe 0
    activationStatusInfoBefore.foreach(assertVotingStatus(_, votesForFeature1, BlockchainFeatureStatus.Undefined, NodeFeatureStatus.Implemented))
  }

  "feature is still in VOTING status on the next voting interval" in {
    activationStatusInfoAfter.foreach(assertVotingStatus(_, 0, BlockchainFeatureStatus.Undefined, NodeFeatureStatus.Implemented))
  }

}
