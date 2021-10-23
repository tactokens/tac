package com.tacplatform.db

import java.nio.file.Files

import cats.Monoid
import com.tacplatform.{NTPTime, TestHelpers}
import com.tacplatform.account.Address
import com.tacplatform.block.Block
import com.tacplatform.common.utils.EitherExt2
import com.tacplatform.database.{loadActiveLeases, LevelDBFactory, LevelDBWriter, TestStorageFactory}
import com.tacplatform.events.BlockchainUpdateTriggers
import com.tacplatform.features.{BlockchainFeature, BlockchainFeatures}
import com.tacplatform.history.Domain
import com.tacplatform.lagonaki.mocks.TestBlock
import com.tacplatform.lang.ValidationError
import com.tacplatform.mining.MiningConstraint
import com.tacplatform.settings.{
  loadConfig, BlockchainSettings, FunctionalitySettings, TestSettings, TacSettings,
  TestFunctionalitySettings => TFS
}
import com.tacplatform.state.{Blockchain, BlockchainUpdaterImpl, Diff}
import com.tacplatform.state.diffs.{produce, BlockDiffer}
import com.tacplatform.state.reader.CompositeBlockchain
import com.tacplatform.state.utils.TestLevelDB
import com.tacplatform.transaction.{Asset, Transaction}
import com.tacplatform.transaction.smart.script.trace.TracedResult
import monix.reactive.Observer
import monix.reactive.subjects.{PublishSubject, Subject}
import org.iq80.leveldb.{DB, Options}
import org.scalatest.{Matchers, Suite}

trait WithState extends DBCacheSettings with Matchers with NTPTime { _: Suite =>
  protected val ignoreSpendableBalanceChanged: Subject[(Address, Asset), (Address, Asset)] = PublishSubject()
  protected val ignoreBlockchainUpdateTriggers: BlockchainUpdateTriggers                   = BlockchainUpdateTriggers.noop

  private[this] val currentDbInstance = new ThreadLocal[DB]
  protected def db: DB                = currentDbInstance.get()

  protected def tempDb[A](f: DB => A): A = {
    val path = Files.createTempDirectory("lvl-temp").toAbsolutePath
    val db   = LevelDBFactory.factory.open(path.toFile, new Options().createIfMissing(true))
    currentDbInstance.set(db)
    try {
      f(db)
    } finally {
      db.close()
      currentDbInstance.remove()
      TestHelpers.deleteRecursively(path)
    }
  }

  protected def withLevelDBWriter[A](ws: TacSettings)(test: LevelDBWriter => A): A = tempDb { db =>
    val (_, ldb) = TestStorageFactory(
      ws,
      db,
      ntpTime,
      ignoreSpendableBalanceChanged,
      ignoreBlockchainUpdateTriggers
    )
    test(ldb)
  }

  protected def withLevelDBWriter[A](bs: BlockchainSettings)(test: LevelDBWriter => A): A =
    withLevelDBWriter(TestSettings.Default.copy(blockchainSettings = bs))(test)

  def withLevelDBWriter[A](fs: FunctionalitySettings)(test: LevelDBWriter => A): A =
    withLevelDBWriter(TestLevelDB.createTestBlockchainSettings(fs))(test)

  def assertDiffEi(preconditions: Seq[Block], block: Block, fs: FunctionalitySettings = TFS.Enabled)(
      assertion: Either[ValidationError, Diff] => Unit
  ): Unit = withLevelDBWriter(fs) { state =>
    assertDiffEi(preconditions, block, state)(assertion)
  }

  def assertDiffEi(preconditions: Seq[Block], block: Block, state: LevelDBWriter)(
      assertion: Either[ValidationError, Diff] => Unit
  ): Unit = {
    def differ(blockchain: Blockchain, b: Block) = BlockDiffer.fromBlock(blockchain, None, b, MiningConstraint.Unlimited)

    preconditions.foreach { precondition =>
      val BlockDiffer.Result(preconditionDiff, preconditionFees, totalFee, _, _) = differ(state, precondition).explicitGet()
      state.append(preconditionDiff, preconditionFees, totalFee, None, precondition.header.generationSignature, precondition)
    }
    val totalDiff1 = differ(state, block)
    assertion(totalDiff1.map(_.diff))
  }

  def assertDiffEiTraced(preconditions: Seq[Block], block: Block, fs: FunctionalitySettings = TFS.Enabled)(
      assertion: TracedResult[ValidationError, Diff] => Unit
  ): Unit = withLevelDBWriter(fs) { state =>
    def differ(blockchain: Blockchain, b: Block) = BlockDiffer.fromBlockTraced(blockchain, None, b, MiningConstraint.Unlimited)

    preconditions.foreach { precondition =>
      val BlockDiffer.Result(preconditionDiff, preconditionFees, totalFee, _, _) = differ(state, precondition).resultE.explicitGet()
      state.append(preconditionDiff, preconditionFees, totalFee, None, precondition.header.generationSignature, precondition)
    }
    val totalDiff1 = differ(state, block)
    assertion(totalDiff1.map(_.diff))
  }

  private def assertDiffAndState(preconditions: Seq[Block], block: Block, fs: FunctionalitySettings, withNg: Boolean)(
      assertion: (Diff, Blockchain) => Unit
  ): Unit = withLevelDBWriter(fs) { state =>
    def differ(blockchain: Blockchain, prevBlock: Option[Block], b: Block): Either[ValidationError, BlockDiffer.Result] =
      BlockDiffer.fromBlock(blockchain, if (withNg) prevBlock else None, b, MiningConstraint.Unlimited)

    preconditions.foldLeft[Option[Block]](None) { (prevBlock, curBlock) =>
      val BlockDiffer.Result(diff, fees, totalFee, _, _) = differ(state, prevBlock, curBlock).explicitGet()
      state.append(diff, fees, totalFee, None, curBlock.header.generationSignature, curBlock)
      Some(curBlock)
    }

    val BlockDiffer.Result(diff, fees, totalFee, _, _) = differ(state, preconditions.lastOption, block).explicitGet()
    val cb                                             = CompositeBlockchain(state, Some(diff))
    assertion(diff, cb)

    state.append(diff, fees, totalFee, None, block.header.generationSignature, block)
    assertion(diff, state)
  }

  def assertNgDiffState(preconditions: Seq[Block], block: Block, fs: FunctionalitySettings = TFS.Enabled)(
      assertion: (Diff, Blockchain) => Unit
  ): Unit =
    assertDiffAndState(preconditions, block, fs, withNg = true)(assertion)

  def assertDiffAndState(preconditions: Seq[Block], block: Block, fs: FunctionalitySettings = TFS.Enabled)(
      assertion: (Diff, Blockchain) => Unit
  ): Unit =
    assertDiffAndState(preconditions, block, fs, withNg = false)(assertion)

  def assertDiffAndState(fs: FunctionalitySettings)(test: (Seq[Transaction] => Either[ValidationError, Unit]) => Unit): Unit =
    withLevelDBWriter(fs) { state =>
      def differ(blockchain: Blockchain, b: Block) = BlockDiffer.fromBlock(blockchain, None, b, MiningConstraint.Unlimited)

      test(txs => {
        val nextHeight = state.height + 1
        val isProto    = state.activatedFeatures.get(BlockchainFeatures.BlockV5.id).exists(nextHeight > 1 && nextHeight >= _)
        val block      = TestBlock.create(txs, if (isProto) Block.ProtoBlockVersion else Block.PlainBlockVersion)
        differ(state, block).map(
          diff => state.append(diff.diff, diff.carry, diff.totalFee, None, block.header.generationSignature.take(Block.HitSourceLength), block)
        )
      })
    }

  def assertBalanceInvariant(diff: Diff): Unit = {
    val portfolioDiff = Monoid.combineAll(diff.portfolios.values)
    portfolioDiff.balance shouldBe 0
    portfolioDiff.effectiveBalance shouldBe 0
    portfolioDiff.assets.values.foreach(_ shouldBe 0)
  }

  def assertLeft(preconditions: Seq[Block], block: Block, fs: FunctionalitySettings = TFS.Enabled)(errorMessage: String): Unit =
    assertDiffEi(preconditions, block, fs)(_ should produce(errorMessage))
}

trait WithDomain extends WithState { _: Suite =>
  implicit class TacSettingsOps(ws: TacSettings) {
    def withFeatures(fs: BlockchainFeature*): TacSettings = {
      val functionalitySettings = ws.blockchainSettings.functionalitySettings.copy(preActivatedFeatures = fs.map(_.id -> 0).toMap)
      ws.copy(blockchainSettings = ws.blockchainSettings.copy(functionalitySettings = functionalitySettings))
    }

    def addFeatures(fs: BlockchainFeature*): TacSettings = {
      val newFeatures           = ws.blockchainSettings.functionalitySettings.preActivatedFeatures ++ fs.map(_.id -> 0)
      val functionalitySettings = ws.blockchainSettings.functionalitySettings.copy(preActivatedFeatures = newFeatures)
      ws.copy(blockchainSettings = ws.blockchainSettings.copy(functionalitySettings = functionalitySettings))
    }
  }

  lazy val SettingsFromDefaultConfig: TacSettings =
    TacSettings.fromRootConfig(loadConfig(None))

  def domainSettingsWithFS(fs: FunctionalitySettings): TacSettings =
    SettingsFromDefaultConfig.copy(
      blockchainSettings = SettingsFromDefaultConfig.blockchainSettings.copy(functionalitySettings = fs)
    )

  def domainSettingsWithPreactivatedFeatures(fs: BlockchainFeature*): TacSettings =
    domainSettingsWithFeatures(fs.map(_ -> 0): _*)

  def domainSettingsWithFeatures(fs: (BlockchainFeature, Int)*): TacSettings =
    domainSettingsWithFS(SettingsFromDefaultConfig.blockchainSettings.functionalitySettings.copy(preActivatedFeatures = fs.map { case (f, h) => f.id -> h }.toMap))

  object DomainPresets {
    val NG = domainSettingsWithPreactivatedFeatures(
      BlockchainFeatures.MassTransfer, // Removes limit of 100 transactions per block
      BlockchainFeatures.NG
    )

    val RideV4 = NG.addFeatures(
      BlockchainFeatures.SmartAccounts,
      BlockchainFeatures.DataTransaction,
      BlockchainFeatures.Ride4DApps,
      BlockchainFeatures.SmartAssets,
      BlockchainFeatures.BlockV5
    )

    val RideV5 = RideV4.addFeatures(BlockchainFeatures.SynchronousCalls)
  }


  def withDomain[A](settings: TacSettings = SettingsFromDefaultConfig)(
      test: Domain => A
  ): A =
    withLevelDBWriter(settings) { blockchain =>
      var domain: Domain = null
      val bcu = new BlockchainUpdaterImpl(
        blockchain,
        Observer.stopped,
        settings,
        ntpTime,
        BlockchainUpdateTriggers.combined(domain.triggers),
        loadActiveLeases(db, _, _)
      )
      domain = Domain(db, bcu, blockchain, settings)
      try test(domain)
      finally bcu.shutdown()
    }
}
