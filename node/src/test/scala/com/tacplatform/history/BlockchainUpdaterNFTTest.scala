package com.tacplatform.history

import com.tacplatform._
import com.tacplatform.account.Address
import com.tacplatform.block.{Block, MicroBlock}
import com.tacplatform.common.state.ByteStr
import com.tacplatform.common.utils.EitherExt2
import com.tacplatform.database.{KeyTags, Keys}
import com.tacplatform.features.BlockchainFeatures
import com.tacplatform.history.Domain.BlockchainUpdaterExt
import com.tacplatform.lagonaki.mocks.TestBlock
import com.tacplatform.lang.v1.FunctionHeader
import com.tacplatform.lang.v1.compiler.Terms.FUNCTION_CALL
import com.tacplatform.lang.v1.estimator.v2.ScriptEstimatorV2
import com.tacplatform.state.diffs
import com.tacplatform.transaction.Asset.IssuedAsset
import com.tacplatform.transaction.GenesisTransaction
import com.tacplatform.transaction.assets.IssueTransaction
import com.tacplatform.transaction.smart.InvokeScriptTransaction
import com.tacplatform.transaction.smart.script.ScriptCompiler
import org.scalacheck.Gen
import org.scalatest._
import org.scalatestplus.scalacheck.{ScalaCheckPropertyChecks => PropertyChecks}

class BlockchainUpdaterNFTTest
    extends PropSpec
    with PropertyChecks
    with DomainScenarioDrivenPropertyCheck
    with Matchers
    with EitherMatchers
    with TransactionGen
    with BlocksTransactionsHelpers
    with NoShrink {

  property("nft list should be consistent with transfer") {
    forAll(Preconditions.nftTransfer()) {
      case (issue, Seq(firstAccount, secondAccount), Seq(genesisBlock, issueBlock, keyBlock, postBlock), Seq(microBlock)) =>
        withDomain(settingsWithFeatures(BlockchainFeatures.NG, BlockchainFeatures.ReduceNFTFee)) { d =>
          d.blockchainUpdater.processBlock(genesisBlock) should beRight
          d.blockchainUpdater.processBlock(issueBlock) should beRight
          d.blockchainUpdater.processBlock(keyBlock) should beRight

          d.nftList(firstAccount).map(_._1.id) shouldBe Seq(issue.id())
          d.nftList(secondAccount) shouldBe Nil

          d.blockchainUpdater.processMicroBlock(microBlock) should beRight
          d.nftList(firstAccount) shouldBe Nil
          d.nftList(secondAccount).map(_._1.id) shouldBe Seq(issue.id())

          d.blockchainUpdater.processBlock(postBlock) should beRight
          d.nftList(firstAccount) shouldBe Nil
          d.nftList(secondAccount).map(_._1.id) shouldBe Seq(issue.id())
        }
    }
  }

  property("nft list should be consistent with invokescript") {
    forAll(Preconditions.nftInvokeScript()) {
      case (issue, Seq(firstAccount, secondAccount), Seq(genesisBlock, issueBlock, keyBlock, postBlock), Seq(microBlock)) =>
        withDomain(
          settingsWithFeatures(
            BlockchainFeatures.NG,
            BlockchainFeatures.ReduceNFTFee,
            BlockchainFeatures.SmartAccounts,
            BlockchainFeatures.Ride4DApps
          )
        ) { d =>
          d.blockchainUpdater.processBlock(genesisBlock) should beRight
          d.blockchainUpdater.processBlock(issueBlock) should beRight
          d.blockchainUpdater.processBlock(keyBlock) should beRight

          d.nftList(firstAccount).map(_._1.id) shouldBe Seq(issue.id())
          d.nftList(secondAccount) shouldBe Nil

          d.blockchainUpdater.processMicroBlock(microBlock) should beRight
          d.nftList(firstAccount) shouldBe Nil
          d.nftList(secondAccount).map(_._1.id) shouldBe Seq(issue.id())

          d.blockchainUpdater.processBlock(postBlock) should beRight
          d.nftList(firstAccount) shouldBe Nil
          d.nftList(secondAccount).map(_._1.id) shouldBe Seq(issue.id())
        }
    }
  }

  property("nft list should be persisted only once independently to using bloom filters") {
    forAll(Preconditions.nftList()) {
      case (issue, Seq(firstAccount, secondAccount), Seq(genesisBlock, firstBlock, secondBlock, postBlock)) =>
        def assert(d: Domain): Assertion = {
          import com.tacplatform.database.DBExt

          d.blockchainUpdater.processBlock(genesisBlock) should beRight
          d.blockchainUpdater.processBlock(firstBlock) should beRight
          d.blockchainUpdater.processBlock(secondBlock) should beRight
          d.blockchainUpdater.processBlock(postBlock) should beRight

          d.nftList(firstAccount).map(_._1.id) shouldBe Seq(issue.id())
          d.nftList(secondAccount) shouldBe Nil

          val persistedNfts = Seq.newBuilder[IssuedAsset]
          d.db.readOnly { ro =>
            val addressId = ro.get(Keys.addressId(firstAccount)).get
            ro.iterateOver(KeyTags.NftPossession.prefixBytes ++ addressId.toByteArray) { e =>
              persistedNfts += IssuedAsset(ByteStr(e.getKey.takeRight(32)))
            }
          }

          persistedNfts.result() shouldBe Seq(IssuedAsset(issue.id()))
        }

        val settings = settingsWithFeatures(BlockchainFeatures.NG, BlockchainFeatures.ReduceNFTFee)
        withDomain(settings)(assert)
        withDomain(settings.copy(dbSettings = settings.dbSettings.copy(useBloomFilter = true)))(assert)
    }
  }

  private[this] object Preconditions {
    import UnsafeBlocks._

    def nftTransfer(): Gen[(IssueTransaction, Seq[Address], Seq[Block], Seq[MicroBlock])] = {
      for {
        richAccount   <- accountGen
        secondAccount <- accountGen
        blockTime = ntpNow
        issue    <- QuickTX.nftIssue(richAccount, Gen.const(blockTime))
        transfer <- QuickTX.transferAsset(issue.asset, richAccount, secondAccount.toAddress, 1, Gen.const(blockTime))
      } yield {
        val genesisBlock = unsafeBlock(
          reference = randomSig,
          txs = Seq(GenesisTransaction.create(richAccount.toAddress, diffs.ENOUGH_AMT, 0).explicitGet()),
          signer = TestBlock.defaultSigner,
          version = 3.toByte,
          timestamp = 0
        )

        val issueBlock = unsafeBlock(
          genesisBlock.signature,
          Seq(issue),
          richAccount,
          3.toByte,
          blockTime
        )

        val (keyBlock, microBlocks) = unsafeChainBaseAndMicro(
          totalRefTo = issueBlock.signature,
          base = Seq(),
          micros = Seq(Seq(transfer)),
          signer = richAccount,
          version = 3.toByte,
          blockTime
        )

        val (postBlock, _) = unsafeChainBaseAndMicro(
          totalRefTo = microBlocks.last.totalResBlockSig,
          base = Seq(),
          micros = Seq(),
          signer = richAccount,
          version = 3.toByte,
          blockTime
        )
        (issue, Seq(richAccount.toAddress, secondAccount.toAddress), Seq(genesisBlock, issueBlock, keyBlock, postBlock), microBlocks)
      }
    }

    def nftInvokeScript(): Gen[(IssueTransaction, Seq[Address], Seq[Block], Seq[MicroBlock])] = {
      for {
        richAccount   <- accountGen
        secondAccount <- accountGen
        blockTime = ntpNow
        issue <- QuickTX.nftIssue(richAccount, Gen.const(blockTime))
        setScript <- {
          val scriptText =
            s"""
               |{-# STDLIB_VERSION 3 #-}
               |{-# CONTENT_TYPE DAPP #-}
               |{-# SCRIPT_TYPE ACCOUNT #-}
               |
               |@Callable(i)
               |func nftTransfer() = {
               |    let pmt = i.payment.extract()
               |    TransferSet([
               |            ScriptTransfer(this, pmt.amount, pmt.assetId)
               |        ])
               |}
               |
               | @Verifier(t)
               | func verify() = {
               |  true
               | }
               |
               |
              """.stripMargin
          val (script, _) = ScriptCompiler.compile(scriptText, ScriptEstimatorV2).explicitGet()
          QuickTX.setScript(secondAccount, script, Gen.const(blockTime))
        }
        invokeScript <- {
          val fc = FUNCTION_CALL(FunctionHeader.User("nftTransfer"), Nil)
          QuickTX.invokeScript(
            richAccount,
            secondAccount.toAddress,
            fc,
            Seq(InvokeScriptTransaction.Payment(1, issue.asset)),
            Gen.const(blockTime)
          )
        }
      } yield {
        val genesisBlock = unsafeBlock(
          reference = randomSig,
          txs = Seq(
            GenesisTransaction.create(richAccount.toAddress, diffs.ENOUGH_AMT, 0).explicitGet(),
            GenesisTransaction.create(secondAccount.toAddress, 1000000, 0).explicitGet()
          ),
          signer = TestBlock.defaultSigner,
          version = 3,
          timestamp = 0
        )

        val issueBlock = unsafeBlock(
          genesisBlock.signature,
          Seq(issue, setScript),
          richAccount,
          3,
          blockTime
        )

        val (keyBlock, microBlocks) = unsafeChainBaseAndMicro(
          totalRefTo = issueBlock.signature,
          base = Seq(),
          micros = Seq(Seq(invokeScript)),
          signer = richAccount,
          version = 3,
          blockTime
        )

        val (postBlock, _) = unsafeChainBaseAndMicro(
          totalRefTo = microBlocks.last.totalResBlockSig,
          base = Seq(),
          micros = Seq(),
          signer = richAccount,
          version = 3,
          blockTime
        )
        (issue, Seq(richAccount.toAddress, secondAccount.toAddress), Seq(genesisBlock, issueBlock, keyBlock, postBlock), microBlocks)
      }
    }

    def nftList(): Gen[(IssueTransaction, Seq[Address], Seq[Block])] = {
      for {
        firstAccount  <- accountGen
        secondAccount <- accountGen
        blockTime = ntpNow
        issue     <- QuickTX.nftIssue(firstAccount, Gen.const(blockTime))
        transfer1 <- QuickTX.transferAsset(issue.asset, firstAccount, secondAccount.toAddress, 1, Gen.const(blockTime))
        transfer2 <- QuickTX.transferAsset(issue.asset, secondAccount, firstAccount.toAddress, 1, Gen.const(blockTime))
      } yield {
        val genesisBlock = unsafeBlock(
          reference = randomSig,
          txs = Seq(
            GenesisTransaction.create(firstAccount.toAddress, diffs.ENOUGH_AMT / 2, 0).explicitGet(),
            GenesisTransaction.create(secondAccount.toAddress, diffs.ENOUGH_AMT / 2, 0).explicitGet(),
            issue
          ),
          signer = TestBlock.defaultSigner,
          version = 3.toByte,
          timestamp = blockTime
        )

        val firstBlock = unsafeBlock(
          genesisBlock.signature,
          Seq(transfer1),
          firstAccount,
          3.toByte,
          blockTime
        )

        val secondBlock = unsafeBlock(
          firstBlock.signature,
          Seq(transfer2),
          firstAccount,
          3.toByte,
          blockTime
        )

        val postBlock = unsafeBlock(
          secondBlock.signature,
          Seq.empty,
          firstAccount,
          3.toByte,
          blockTime
        )

        (issue, Seq(firstAccount.toAddress, secondAccount.toAddress), Seq(genesisBlock, firstBlock, secondBlock, postBlock))
      }
    }
  }
}
