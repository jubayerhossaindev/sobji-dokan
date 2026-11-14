package com.example.qa

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.backup.BackupManager
import com.example.data.backup.SobjiKhataBackupPayload
import com.example.data.local.AppDatabase
import com.example.data.local.ExpenseCategory
import com.example.data.local.ExpenseEntity
import com.example.data.local.PartyType
import com.example.data.local.PaymentMethod
import com.example.data.local.SaleEntity
import com.example.data.local.UnitType
import com.example.data.local.VegetableCategory
import com.example.data.local.VegetableEntity
import com.example.data.prefs.ShopPreferencesRepository
import com.example.data.repository.ShopRepository
import com.example.domain.BusinessCalculator
import com.example.ui.ShopViewModel
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FinalHardeningQaTest {

  private lateinit var app: Application
  private lateinit var db: AppDatabase

  @Before
  fun setup() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    app = ApplicationProvider.getApplicationContext()
    AppDatabase.resetInstanceForTest()
    app.deleteDatabase("sobji_khata.db")
    runBlocking { ShopPreferencesRepository(app).resetForTest() }
    db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
  }

  @After
  fun tearDown() {
    db.close()
    AppDatabase.resetInstanceForTest()
    runBlocking { ShopPreferencesRepository(app).resetForTest() }
    Dispatchers.resetMain()
  }

  @Test
  fun `TC-HARD-01 BusinessCalculator safely handles NaN and Infinity without throwing NumberFormatException`() {
    assertEquals(0.0, BusinessCalculator.calculateLineTotal(1000L, UnitType.KG, Double.NaN), 0.0001)
    assertEquals(
      0.0,
      BusinessCalculator.calculateLineTotal(1000L, UnitType.KG, Double.POSITIVE_INFINITY),
      0.0001,
    )
    assertEquals(
      0.0,
      BusinessCalculator.calculateWastageLoss(500L, Double.NaN, UnitType.KG),
      0.0001,
    )
    assertEquals(
      0.0,
      BusinessCalculator.calculateWastageLoss(500L, Double.NEGATIVE_INFINITY, UnitType.KG),
      0.0001,
    )
    assertEquals(
      30.0,
      BusinessCalculator.calculateWeightedAverageCost(1000L, 30.0, 1000L, Double.NaN, UnitType.KG),
      0.0001,
    )
    assertEquals("৳ 0", BusinessCalculator.formatTaka(Double.NaN, false))
    assertEquals("৳ ০", BusinessCalculator.formatTaka(Double.POSITIVE_INFINITY, true))
  }

  @Test
  fun `TC-HARD-02 formatNumber stays consistent with US separators even under German or French OS locale`() {
    val prev = Locale.getDefault()
    try {
      Locale.setDefault(Locale.GERMANY)
      assertEquals("৳ 1,250.5", BusinessCalculator.formatTaka(1250.5, useBengaliDigits = false))
      assertEquals("৳ ১,২৫০", BusinessCalculator.formatTaka(1250.0, useBengaliDigits = true))
    } finally {
      Locale.setDefault(prev)
    }
  }

  @Test
  fun `TC-HARD-03 ShopRepository rejects NaN Infinity negative stock empty items and blank party names`() =
    runTest {
      val repo = ShopRepository(db.shopDao())
      assertTrue(
        repo
          .saveVegetable(
            null,
            "টমেটো",
            VegetableCategory.FRUIT_VEG,
            UnitType.KG,
            Double.NaN,
            50.0,
            1000L,
            0L,
          )
          .isFailure
      )
      assertTrue(
        repo
          .saveVegetable(
            null,
            "টমেটো",
            VegetableCategory.FRUIT_VEG,
            UnitType.KG,
            30.0,
            Double.POSITIVE_INFINITY,
            1000L,
            0L,
          )
          .isFailure
      )

      var emptySaleFailed = false
      try {
        repo.completeSale(
          null,
          "ক্রেতা",
          0.0,
          0.0,
          0.0,
          0.0,
          0.0,
          PaymentMethod.CASH,
          "",
          emptyList(),
        )
      } catch (_: IllegalArgumentException) {
        emptySaleFailed = true
      }
      assertTrue(emptySaleFailed)

      var blankPartyFailed = false
      try {
        repo.addParty("   ", "017", PartyType.CUSTOMER, "")
      } catch (_: IllegalArgumentException) {
        blankPartyFailed = true
      }
      assertTrue(blankPartyFailed)

      var invalidExpenseFailed = false
      try {
        repo.saveExpense(null, ExpenseCategory.RENT, Double.NaN, PaymentMethod.CASH, "", 1000L)
      } catch (_: IllegalArgumentException) {
        invalidExpenseFailed = true
      }
      assertTrue(invalidExpenseFailed)
    }

  @Test
  fun `TC-HARD-04 DAO timestamp ties sort deterministically by id DESC`() = runTest {
    val dao = db.shopDao()
    val fixedTs = 1_700_000_000_000L
    val id1 =
      dao.insertSale(
        SaleEntity(
          customerName = "প্রথম",
          subtotal = 50.0,
          totalAmount = 50.0,
          paidAmount = 50.0,
          timestamp = fixedTs,
        )
      )
    val id2 =
      dao.insertSale(
        SaleEntity(
          customerName = "দ্বিতীয়",
          subtotal = 80.0,
          totalAmount = 80.0,
          paidAmount = 80.0,
          timestamp = fixedTs,
        )
      )
    val id3 =
      dao.insertSale(
        SaleEntity(
          customerName = "তৃতীয়",
          subtotal = 120.0,
          totalAmount = 120.0,
          paidAmount = 120.0,
          timestamp = fixedTs,
        )
      )

    val ordered = dao.observeAllSales().first()
    assertEquals(listOf(id3, id2, id1), ordered.map { it.id })

    val exp1 =
      dao.insertOrUpdateExpense(
        ExpenseEntity(category = ExpenseCategory.RENT, amount = 100.0, timestamp = fixedTs)
      )
    val exp2 =
      dao.insertOrUpdateExpense(
        ExpenseEntity(category = ExpenseCategory.PACKAGING, amount = 50.0, timestamp = fixedTs)
      )
    val orderedExp = dao.observeAllExpenses().first()
    assertEquals(listOf(exp2, exp1), orderedExp.map { it.id })
  }

  @Test
  fun `TC-HARD-05 BackupManager rejects semantic tampering with negative amounts or blank vegetable names`() =
    runTest {
      val dao = db.shopDao()
      val prefsRepo = ShopPreferencesRepository(app)
      val backupManager = BackupManager(app, dao, prefsRepo)

      val badExpensePayload =
        File.createTempFile("bad_neg_exp", ".json", app.cacheDir).apply {
          writeText(
            """{"schemaTag":"${SobjiKhataBackupPayload.SCHEMA_TAG}","version":1,"exportedAt":1700000000000,"expenses":[{"id":1,"category":"RENT","amount":-200.0,"paymentMethod":"CASH","note":"","timestamp":1700000000000,"isCancelled":false}]}"""
          )
        }
      assertTrue(backupManager.validateBackupUri(Uri.fromFile(badExpensePayload)).isFailure)

      val badVegPayload =
        File.createTempFile("bad_blank_veg", ".json", app.cacheDir).apply {
          writeText(
            """{"schemaTag":"${SobjiKhataBackupPayload.SCHEMA_TAG}","version":1,"exportedAt":1700000000000,"vegetables":[{"id":1,"name":"   ","category":"ROOT","unitType":"KG","stockUnits":0,"avgCostPerUnit":10.0,"sellingPricePerUnit":20.0,"lowStockThresholdUnits":1000,"isArchived":false}]}"""
          )
        }
      assertTrue(backupManager.validateBackupUri(Uri.fromFile(badVegPayload)).isFailure)

      badExpensePayload.delete()
      badVegPayload.delete()
    }

  @Test
  fun `TC-HARD-06 ShopViewModel rejects non-finite cart inputs blank shop profile updates and bad PINs cleanly`() =
    runBlocking {
      withTimeout(10_000L) {
        val vm = ShopViewModel(app)
        val job = launch { vm.uiState.collect {} }
        vm.uiState.first { it.isLoaded }

        vm.completeInitialSetup("সবুজ শপ", "কামাল", useBengaliDigits = true, seedDefaults = false)
        val initial =
          vm.uiState.first {
            it.preferences.isSetupComplete && it.preferences.shopName == "সবুজ শপ"
          }
        assertEquals("সবুজ শপ", initial.preferences.shopName)

        // Blank shop name update must be ignored without overwriting valid profile
        vm.updateShopProfile("   ", "অন্য নাম")
        assertEquals("সবুজ শপ", vm.uiState.value.preferences.shopName)

        // Invalid PIN (non-digit or wrong length) must not set a PIN
        vm.setPinCode("12")
        assertEquals("", vm.uiState.value.preferences.pinCode)

        // NaN rate must never enter POS cart or purchase draft
        val veg =
          VegetableEntity(
            id = 99L,
            name = "গাজর",
            category = VegetableCategory.ROOT,
            unitType = UnitType.KG,
            stockUnits = 5_000L,
            avgCostPerUnit = 40.0,
            sellingPricePerUnit = 60.0,
          )
        vm.addToPosCart(veg, 1_000L, Double.NaN)
        vm.addToPosCart(veg, 1_000L, Double.POSITIVE_INFINITY)
        vm.addPurchaseDraftItem(veg, 1_000L, Double.NaN)
        assertEquals(0, vm.uiState.value.posCart.size)
        assertEquals(0, vm.uiState.value.purchaseCart.size)

        job.cancel()
      }
    }

  @Test
  fun `TC-HARD-07 ShopViewModel enforces 5-attempt PIN lockout across restarts and resets after success`() =
    runBlocking {
      withTimeout(12_000L) {
        val prefsRepo = ShopPreferencesRepository(app)
        prefsRepo.saveInitialSetup("নিরাপদ শপ", "হাসান", true)
        prefsRepo.setPinCode("4321")

        val vm = ShopViewModel(app)
        val job = launch { vm.uiState.collect {} }
        vm.uiState.first { it.isLoaded && it.preferences.pinCode.isNotBlank() }

        // 4 failed attempts keep lockout inactive
        repeat(4) {
          val ok = vm.unlockWithPin("0000")
          assertTrue(!ok)
        }
        assertEquals(0L, vm.uiState.value.pinLockoutUntilMs)

        // 5th failed attempt engages 30s lockout and persists it in DataStore
        assertTrue(!vm.unlockWithPin("0000"))
        val lockedState =
          vm.uiState.first {
            it.pinLockoutUntilMs > System.currentTimeMillis() &&
              it.preferences.failedPinAttempts == 5
          }
        assertTrue(lockedState.pinLockoutUntilMs > System.currentTimeMillis())
        assertEquals(5, lockedState.preferences.failedPinAttempts)

        // Even valid PIN is rejected while locked out
        assertTrue(!vm.unlockWithPin("4321"))
        assertTrue(!vm.uiState.value.isUnlocked)

        // Simulate cooldown elapsed -> valid PIN unlocks and resets attempts
        prefsRepo.updatePinAttemptState(5, System.currentTimeMillis() - 1000L)
        vm.uiState.first { it.preferences.pinLockoutUntilMs < System.currentTimeMillis() }
        val vmAfterCooldown = ShopViewModel(app)
        val job2 = launch { vmAfterCooldown.uiState.collect {} }
        vmAfterCooldown.uiState.first { it.isLoaded && it.preferences.pinCode.isNotBlank() }
        assertTrue(vmAfterCooldown.unlockWithPin("4321"))
        val unlockedState =
          vmAfterCooldown.uiState.first {
            it.isUnlocked &&
              it.preferences.failedPinAttempts == 0 &&
              it.preferences.pinLockoutUntilMs == 0L
          }
        assertEquals(0, unlockedState.preferences.failedPinAttempts)
        assertEquals(0L, unlockedState.preferences.pinLockoutUntilMs)

        job2.cancel()
        job.cancel()
      }
    }

  @Test
  fun `TC-HARD-08 ShopViewModel bulk updates vegetable prices and cancels due payment restoring party balance`() =
    runBlocking {
      withTimeout(12_000L) {
        val vm = ShopViewModel(app)
        val job = launch { vm.uiState.collect {} }
        vm.uiState.first { it.isLoaded }

        vm.completeInitialSetup(
          "সবুজ ভান্ডার",
          "রহিম",
          useBengaliDigits = true,
          seedDefaults = true,
        )
        var state = vm.uiState.first { it.isLoaded && it.activeVegetables.size == 15 }
        val veg1 = state.activeVegetables[0]
        val veg2 = state.activeVegetables[1]

        var bulkDone = false
        vm.bulkUpdateVegetableSellingPrices(mapOf(veg1.id to 77.5, veg2.id to 92.0)) {
          bulkDone = true
        }
        state =
          vm.uiState.first {
            !it.isSaving &&
              it.activeVegetables.first { v -> v.id == veg1.id }.sellingPricePerUnit == 77.5 &&
              it.activeVegetables.first { v -> v.id == veg2.id }.sellingPricePerUnit == 92.0
          }
        assertTrue(bulkDone)

        val customer = vm.createPartyImmediate("সালাম ভাই", "01722", PartyType.CUSTOMER)
        vm.addToPosCart(state.activeVegetables.first { it.id == veg1.id }, 2_000L, 100.0)
        vm.completePosSale(customer.id, customer.name, 0.0, 50.0, PaymentMethod.CREDIT, "") {}
        state = vm.uiState.first { it.sales.isNotEmpty() && it.posCart.isEmpty() }
        assertEquals(
          150.0,
          state.customerBalances.first { it.party.id == customer.id }.balanceDue,
          0.01,
        )

        vm.recordDuePayment(customer, 100.0, PaymentMethod.CASH, "আংশিক")
        state = vm.uiState.first { it.duePayments.isNotEmpty() }
        assertEquals(
          50.0,
          state.customerBalances.first { it.party.id == customer.id }.balanceDue,
          0.01,
        )

        val paymentId = state.duePayments.first().id
        vm.cancelDuePayment(paymentId)
        state = vm.uiState.first { it.duePayments.first().isCancelled }
        assertEquals(
          150.0,
          state.customerBalances.first { it.party.id == customer.id }.balanceDue,
          0.01,
        )

        job.cancel()
      }
    }
}
