package com.example.qa

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.ExpenseCategory
import com.example.data.local.PartyType
import com.example.data.local.PaymentMethod
import com.example.data.local.WastageReason
import com.example.data.prefs.ShopPreferencesRepository
import com.example.ui.ShopViewModel
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ViewModelIntegrationQaTest {

  private lateinit var app: Application
  private lateinit var viewModelStore: ViewModelStore

  @Before
  fun setup() {
    Dispatchers.setMain(UnconfinedTestDispatcher())
    app = ApplicationProvider.getApplicationContext()
    viewModelStore = ViewModelStore()
    AppDatabase.resetInstanceForTest()
    app.deleteDatabase("sobji_khata.db")
    runBlocking { ShopPreferencesRepository(app).resetForTest() }
  }

  @After
  fun tearDown() {
    viewModelStore.clear()
    AppDatabase.resetInstanceForTest()
    runBlocking { ShopPreferencesRepository(app).resetForTest() }
    Dispatchers.resetMain()
  }

  @Test
  fun `TC-INT-01 full seller workflow setup purchase sale wastage credit collection cancel and backup restore clears cart`() =
    runBlocking {
      withTimeout(60_000L) {
        val vm =
          ViewModelProvider(
            viewModelStore,
            ViewModelProvider.AndroidViewModelFactory.getInstance(app),
          )[ShopViewModel::class.java]
        val collectJob = launch { vm.uiState.collect {} }

        // Wait for DataStore + Room initial emission
        vm.uiState.first { it.isLoaded }

        // 1. Onboarding setup with 15 default Bengali vegetables
        vm.completeInitialSetup(
          "বিসমিল্লাহ সবজি ঘর",
          "রফিক",
          useBengaliDigits = true,
          seedDefaults = true,
        )
        var state =
          vm.uiState.first {
            it.isLoaded && it.preferences.isSetupComplete && it.activeVegetables.size == 15
          }
        assertEquals("বিসমিল্লাহ সবজি ঘর", state.preferences.shopName)
        val potato = state.activeVegetables.first { it.name == "আলু" }
        val tomato = state.activeVegetables.first { it.name == "টমেটো" }

        // 2. Wholesale purchase: 10kg potato @ 30 (300) + 5kg tomato @ 40 (200), extraCost = 50,
        // cash paid = 500
        vm.addPurchaseDraftItem(potato, 10_000L, 30.0)
        vm.addPurchaseDraftItem(tomato, 5_000L, 40.0)
        val purchaseDone = CompletableDeferred<Boolean>()
        vm.completePurchase(null, "নগদ বাজার", 50.0, 500.0, "", System.currentTimeMillis()) {
          purchaseDone.complete(true)
        }
        state = vm.uiState.first { it.purchases.isNotEmpty() && it.purchaseCart.isEmpty() }
        assertTrue(purchaseDone.await())
        assertEquals(10_000L, state.activeVegetables.first { it.id == potato.id }.stockUnits)
        assertEquals(5_000L, state.activeVegetables.first { it.id == tomato.id }.stockUnits)

        // 3. Multi-item sale on partial credit to customer করিম:
        // 2kg potato @ 40 (80) + 1kg tomato @ 60 (60) = 140, discount 10 -> net 130, paid 80 -> due
        // 50
        val customer = vm.createPartyImmediate("করিম", "01711000000", PartyType.CUSTOMER)
        vm.addToPosCart(state.activeVegetables.first { it.id == potato.id }, 2_000L, 40.0)
        vm.addToPosCart(state.activeVegetables.first { it.id == tomato.id }, 1_000L, 60.0)
        val saleDone = CompletableDeferred<Boolean>()
        vm.completePosSale(customer.id, customer.name, 10.0, 80.0, PaymentMethod.CREDIT, "") {
          saleDone.complete(true)
        }
        state = vm.uiState.first { it.sales.isNotEmpty() && it.posCart.isEmpty() }
        assertTrue(saleDone.await())
        assertEquals(
          50.0,
          state.customerBalances.first { it.party.id == customer.id }.balanceDue,
          0.01,
        )

        // 4. Collect 30 due from করিম -> remaining due 20
        vm.recordDuePayment(customer, 30.0, PaymentMethod.CASH, "আংশিক আদায়")
        state = vm.uiState.first { it.duePayments.isNotEmpty() }
        assertEquals(
          20.0,
          state.customerBalances.first { it.party.id == customer.id }.balanceDue,
          0.01,
        )

        // 5. Record 500g tomato wastage (cost 40/kg -> loss 20) & 100 rent expense sequentially
        vm.recordWastage(tomato.id, 500L, WastageReason.ROTTEN, "পচা")
        state = vm.uiState.first { it.wastages.isNotEmpty() && !it.isSaving }
        vm.saveExpense(
          null,
          ExpenseCategory.RENT,
          100.0,
          PaymentMethod.CASH,
          "ভাড়া",
          System.currentTimeMillis(),
        )
        state = vm.uiState.first { it.expenses.isNotEmpty() && !it.isSaving }

        // Check todaySummary: netSales=130, COGS=(2*30 + 1*40)=100, gross=30, wastage=20,
        // expenses=100+50=150 -> netProfit=-140
        assertEquals(130.0, state.todaySummary.netSales, 0.01)
        assertEquals(100.0, state.todaySummary.cogs, 0.01)
        assertEquals(20.0, state.todaySummary.wastageLoss, 0.01)
        assertEquals(150.0, state.todaySummary.operatingExpenses, 0.01)
        assertEquals(-140.0, state.todaySummary.netProfit, 0.01)

        // 6. Export backup -> put item in POS cart -> validate & restore -> cart cleared cleanly
        // (BUG-004 fix)
        val backupFile = File.createTempFile("vm_e2e_backup", ".json", app.cacheDir)
        val prevBackupTs = state.preferences.lastBackupTimestamp
        vm.exportBackup(Uri.fromFile(backupFile))
        vm.uiState.first { it.preferences.lastBackupTimestamp > prevBackupTs && !it.isSaving }

        vm.addToPosCart(state.activeVegetables.first { it.id == potato.id }, 1_000L, 40.0)
        vm.uiState.first { it.posCart.size == 1 }

        vm.prepareRestoreFromUri(Uri.fromFile(backupFile))
        vm.pendingRestorePayload.first { it != null }
        assertNotNull(vm.pendingRestorePayload.value)

        vm.confirmRestore()
        vm.uiState.first { it.posCart.isEmpty() && !it.isSaving }
        assertEquals(0, vm.uiState.value.posCart.size)

        backupFile.delete()
        collectJob.cancel()
      }
    }
}
