package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.backup.BackupManager
import com.example.data.backup.SobjiKhataBackupPayload
import com.example.data.local.AppDatabase
import com.example.data.local.ExpenseCategory
import com.example.data.local.PartyEntity
import com.example.data.local.PartyType
import com.example.data.local.PaymentMethod
import com.example.data.local.PurchaseItemEntity
import com.example.data.local.SaleItemEntity
import com.example.data.local.UnitType
import com.example.data.local.VegetableCategory
import com.example.data.local.VegetableEntity
import com.example.data.local.WastageReason
import com.example.data.prefs.AppThemeMode
import com.example.data.prefs.ShopPreferencesRepository
import com.example.data.repository.ShopRepository
import com.example.domain.BusinessCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ShopViewModel(application: Application) : AndroidViewModel(application) {

  private val db = AppDatabase.getInstance(application)
  private val repo = ShopRepository(db.shopDao(), db)
  private val prefsRepo = ShopPreferencesRepository(application)
  private val backupManager = BackupManager(application, db.shopDao(), prefsRepo)

  private val _posCart = MutableStateFlow<List<PosCartItem>>(emptyList())
  private val _purchaseCart = MutableStateFlow<List<PurchaseDraftItem>>(emptyList())
  private val _preselectedPosCustomerId = MutableStateFlow<Long?>(null)
  private val pinLock = PinLockController(prefsRepo, viewModelScope)
  private val _activeSaveCount = MutableStateFlow(0)
  private val writeMutex = Mutex()

  private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val snackbarEvents = _snackbarEvents.asSharedFlow()

  private val backupCoordinator =
    ShopBackupCoordinator(
      backupManager = backupManager,
      snackbarEvents = _snackbarEvents,
      launchSerialized = ::launchSerializedSave,
      scope = viewModelScope,
      onRestored = {
        _posCart.value = emptyList()
        _purchaseCart.value = emptyList()
      },
    )

  val pendingRestorePayload: StateFlow<SobjiKhataBackupPayload?> =
    backupCoordinator.pendingRestorePayload

  private fun launchSerializedSave(block: suspend () -> Unit) {
    _activeSaveCount.update { it + 1 }
    viewModelScope.launch {
      try {
        writeMutex.withLock { block() }
      } finally {
        _activeSaveCount.update { (it - 1).coerceAtLeast(0) }
      }
    }
  }

  private val sessionFlow =
    combine(
      combine(_posCart, _purchaseCart, _preselectedPosCustomerId) { posCart, purchaseCart, custId ->
        Triple(posCart, purchaseCart, custId)
      },
      combine(pinLock.isUnlocked, _activeSaveCount, pinLock.lockoutUntilMs) {
        unlocked,
        saveCount,
        lockoutMs ->
        Triple(unlocked, saveCount > 0, lockoutMs)
      },
    ) { (posCart, purchaseCart, custId), (unlocked, saving, lockoutMs) ->
      ShopSessionState(
        posCart = posCart,
        purchaseCart = purchaseCart,
        preselectedCustomerId = custId,
        isUnlocked = unlocked,
        isSaving = saving,
        memLockoutUntilMs = lockoutMs,
      )
    }

  val uiState: StateFlow<ShopUiState> =
    combine(prefsRepo.preferencesFlow, requireNotNull(repo.atomicSnapshot), sessionFlow) {
        prefs,
        snapshot,
        session ->
        ShopStateAssembler.buildUiState(prefs, snapshot, session)
      }
      .flowOn(Dispatchers.Default)
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
        initialValue = ShopUiState(),
      )

  fun onAppMovedToBackground() {
    pinLock.onAppMovedToBackground(uiState.value.preferences.pinCode.isNotBlank())
  }

  fun onAppReturnedToForeground() {
    pinLock.onAppReturnedToForeground(uiState.value.preferences.pinCode.isNotBlank())
  }

  fun unlockWithPin(pin: String): Boolean = pinLock.unlockWithPin(pin, uiState.value.preferences)

  fun completeInitialSetup(
    shopName: String,
    ownerName: String,
    useBengaliDigits: Boolean,
    seedDefaults: Boolean,
  ) {
    val cleanShopName = shopName.trim()
    if (cleanShopName.isEmpty()) {
      _snackbarEvents.tryEmit("অনুগ্রহ করে দোকানের নাম লিখুন।")
      return
    }
    launchSerializedSave {
      try {
        prefsRepo.saveInitialSetup(cleanShopName, ownerName.trim(), useBengaliDigits)
        if (seedDefaults) {
          repo.seedDefaultVegetablesIfEmpty()
        }
        pinLock.markUnlocked()
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("দোকান সেটআপ সংরক্ষণ করা যায়নি। আবার চেষ্টা করুন।")
      }
    }
  }

  fun updateShopProfile(shopName: String, ownerName: String) {
    val cleanShopName = shopName.trim()
    if (cleanShopName.isEmpty()) {
      _snackbarEvents.tryEmit("দোকানের নাম ফাঁকা রাখা যাবে না।")
      return
    }
    viewModelScope.launch {
      try {
        prefsRepo.updateShopProfile(cleanShopName, ownerName.trim())
        _snackbarEvents.tryEmit("দোকানের তথ্য সংরক্ষিত হয়েছে")
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("দোকানের তথ্য সংরক্ষণ করা যায়নি।")
      }
    }
  }

  fun setUseBengaliDigits(enabled: Boolean) {
    viewModelScope.launch {
      try {
        prefsRepo.setUseBengaliDigits(enabled)
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("সেটিং পরিবর্তন করা যায়নি।")
      }
    }
  }

  fun setShowCreditLedger(enabled: Boolean) {
    viewModelScope.launch {
      try {
        prefsRepo.setShowCreditLedger(enabled)
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("সেটিং পরিবর্তন করা যায়নি।")
      }
    }
  }

  fun setThemeMode(mode: AppThemeMode) {
    viewModelScope.launch {
      try {
        prefsRepo.setThemeMode(mode)
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("থিম পরিবর্তন করা যায়নি।")
      }
    }
  }

  fun preselectPosCustomer(customerId: Long?) {
    _preselectedPosCustomerId.value = customerId
  }

  fun setPinCode(pin: String) {
    val isInvalidPin =
      pin.isNotEmpty() && (pin.length != BusinessCalculator.PIN_LENGTH || !pin.all { it.isDigit() })
    if (isInvalidPin) {
      _snackbarEvents.tryEmit("৪ সংখ্যার সঠিক PIN লিখুন।")
      return
    }
    pinLock.resetOnPinUpdated()
    viewModelScope.launch {
      try {
        prefsRepo.setPinCode(pin)
        if (pin.isEmpty()) {
          _snackbarEvents.tryEmit("PIN লক বন্ধ করা হয়েছে")
        } else {
          pinLock.markUnlocked()
          _snackbarEvents.tryEmit("৪-ডিজিট PIN লক চালু হয়েছে")
        }
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("PIN সংরক্ষণ করা যায়নি।")
      }
    }
  }

  // POS Cart actions
  fun addToPosCart(veg: VegetableEntity, quantityUnits: Long, customRate: Double) {
    if (!customRate.isFinite() || customRate <= 0.0 || quantityUnits <= 0L) return
    val lineTotal = BusinessCalculator.calculateLineTotal(quantityUnits, veg.unitType, customRate)
    if (!lineTotal.isFinite() || lineTotal <= 0.0) return
    val current = _posCart.value.toMutableList()
    val idx = current.indexOfFirst { it.vegetable.id == veg.id }
    val item = PosCartItem(veg, quantityUnits, customRate, lineTotal)
    if (idx >= 0) current[idx] = item else current.add(item)
    _posCart.value = current
  }

  fun removeFromPosCart(vegId: Long) {
    _posCart.value = _posCart.value.filterNot { it.vegetable.id == vegId }
  }

  fun clearPosCart() {
    _posCart.value = emptyList()
  }

  fun completePosSale(
    customerId: Long?,
    customerName: String,
    discount: Double,
    paidAmount: Double,
    paymentMethod: PaymentMethod,
    notes: String = "",
    onSuccess: () -> Unit = {},
  ) {
    if (!discount.isFinite() || !paidAmount.isFinite()) {
      _snackbarEvents.tryEmit("সঠিক টাকার পরিমাণ লিখুন।")
      return
    }
    if (_posCart.value.isEmpty()) return

    launchSerializedSave {
      val cart = _posCart.value
      if (cart.isEmpty()) return@launchSerializedSave
      val subtotal = BusinessCalculator.round2(cart.sumOf { it.lineTotal })
      val cleanDiscount = BusinessCalculator.round2(discount.coerceIn(0.0, subtotal))
      val totalAmount = BusinessCalculator.round2((subtotal - cleanDiscount).coerceAtLeast(0.0))
      val cleanPaid = BusinessCalculator.round2(paidAmount.coerceIn(0.0, totalAmount))
      val due = BusinessCalculator.round2((totalAmount - cleanPaid).coerceAtLeast(0.0))
      try {
        val saleItems =
          cart.map {
            SaleItemEntity(
              saleId = 0L,
              vegetableId = it.vegetable.id,
              vegetableName = it.vegetable.name,
              unitType = it.vegetable.unitType,
              quantityUnits = it.quantityUnits,
              sellingPricePerUnit = it.sellingPricePerUnit,
              costSnapshotPerUnit = it.vegetable.avgCostPerUnit,
              lineTotal = it.lineTotal,
            )
          }
        repo.completeSale(
          customerId = customerId,
          customerName = customerName.ifBlank { "সাধারণ ক্রেতা" },
          subtotal = subtotal,
          discount = cleanDiscount,
          totalAmount = totalAmount,
          paidAmount = cleanPaid,
          dueAmount = due,
          paymentMethod = paymentMethod,
          notes = notes,
          items = saleItems,
        )
        _posCart.value = emptyList()
        _preselectedPosCustomerId.value = null
        val formattedTotal =
          BusinessCalculator.formatTaka(totalAmount, uiState.value.preferences.useBengaliDigits)
        _snackbarEvents.tryEmit("বিক্রি সংরক্ষিত হয়েছে • $formattedTotal")
        onSuccess()
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("তথ্য সংরক্ষণ করা যায়নি। আবার চেষ্টা করুন।")
      }
    }
  }

  fun cancelSale(saleId: Long) {
    launchSerializedSave {
      try {
        repo.cancelSale(saleId)
        _snackbarEvents.tryEmit("বিক্রি বাতিল হয়েছে ও সবজি স্টকে ফিরেছে")
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("বিক্রি বাতিল করা যায়নি। আবার চেষ্টা করুন।")
      }
    }
  }

  // Purchase Draft Actions
  fun addPurchaseDraftItem(veg: VegetableEntity, quantityUnits: Long, unitCost: Double) {
    if (!unitCost.isFinite() || unitCost <= 0.0 || quantityUnits <= 0L) return
    val lineTotal = BusinessCalculator.calculateLineTotal(quantityUnits, veg.unitType, unitCost)
    if (!lineTotal.isFinite() || lineTotal <= 0.0) return
    val list = _purchaseCart.value.toMutableList()
    val idx = list.indexOfFirst { it.vegetable.id == veg.id }
    val item = PurchaseDraftItem(veg, quantityUnits, unitCost, lineTotal)
    if (idx >= 0) list[idx] = item else list.add(item)
    _purchaseCart.value = list
  }

  fun removePurchaseDraftItem(vegId: Long) {
    _purchaseCart.value = _purchaseCart.value.filterNot { it.vegetable.id == vegId }
  }

  fun clearPurchaseDraft() {
    _purchaseCart.value = emptyList()
  }

  fun completePurchase(
    supplierId: Long?,
    supplierName: String,
    extraCost: Double,
    paidAmount: Double,
    notes: String,
    timestamp: Long,
    onSuccess: () -> Unit,
  ) {
    if (!extraCost.isFinite() || !paidAmount.isFinite()) {
      _snackbarEvents.tryEmit("সঠিক টাকার পরিমাণ লিখুন।")
      return
    }
    if (_purchaseCart.value.isEmpty()) return

    launchSerializedSave {
      val draft = _purchaseCart.value
      if (draft.isEmpty()) return@launchSerializedSave
      val itemsTotal = BusinessCalculator.round2(draft.sumOf { it.lineTotal })
      val cleanExtra = BusinessCalculator.round2(extraCost.coerceAtLeast(0.0))
      val cleanPaid = BusinessCalculator.round2(paidAmount.coerceIn(0.0, itemsTotal))
      val due = BusinessCalculator.round2((itemsTotal - cleanPaid).coerceAtLeast(0.0))
      try {
        val entities =
          draft.map {
            PurchaseItemEntity(
              purchaseId = 0L,
              vegetableId = it.vegetable.id,
              vegetableName = it.vegetable.name,
              unitType = it.vegetable.unitType,
              quantityUnits = it.quantityUnits,
              unitCost = it.unitCost,
              lineTotal = it.lineTotal,
            )
          }
        repo.completePurchase(
          supplierId = supplierId,
          supplierName = supplierName.ifBlank { "নগদ বাজার" },
          itemsTotal = itemsTotal,
          extraCost = cleanExtra,
          paidAmount = cleanPaid,
          dueAmount = due,
          notes = notes,
          timestamp = timestamp,
          items = entities,
        )
        _purchaseCart.value = emptyList()
        _snackbarEvents.tryEmit("নতুন ক্রয় ও স্টক আপডেট হয়েছে")
        onSuccess()
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("ক্রয় সংরক্ষণ করা যায়নি। আবার চেষ্টা করুন।")
      }
    }
  }

  fun cancelPurchase(purchaseId: Long) {
    launchSerializedSave {
      try {
        repo.cancelPurchase(purchaseId)
        _snackbarEvents.tryEmit("ক্রয় বাতিল ও স্টক সমন্বয় হয়েছে")
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("ক্রয় বাতিল করা যায়নি। আবার চেষ্টা করুন।")
      }
    }
  }

  // Product Management
  fun saveVegetable(
    existingId: Long?,
    name: String,
    category: VegetableCategory,
    unitType: UnitType,
    avgCost: Double,
    sellingPrice: Double,
    lowStockThreshold: Long,
    initialStock: Long,
    onResult: (Result<Unit>) -> Unit,
  ) {
    launchSerializedSave {
      try {
        val result =
          repo.saveVegetable(
            existingId = existingId,
            name = name,
            category = category,
            unitType = unitType,
            avgCost = avgCost,
            sellingPrice = sellingPrice,
            lowStockThreshold = lowStockThreshold,
            initialStock = initialStock,
          )
        if (result.isSuccess) {
          _snackbarEvents.tryEmit(
            if (existingId == null) "নতুন সবজি যোগ হয়েছে" else "সবজির তথ্য আপডেট হয়েছে"
          )
        }
        onResult(result)
      } catch (e: Exception) {
        _snackbarEvents.tryEmit("সবজির তথ্য সংরক্ষণ করা যায়নি।")
        onResult(Result.failure(e))
      }
    }
  }

  fun toggleArchiveVegetable(veg: VegetableEntity) {
    launchSerializedSave {
      try {
        repo.setVegetableArchived(veg, !veg.isArchived)
        _snackbarEvents.tryEmit(
          if (!veg.isArchived) "${veg.name} আর্কাইভ করা হয়েছে" else "${veg.name} পুনরায় চালু হয়েছে"
        )
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("সবজির অবস্থা পরিবর্তন করা যায়নি।")
      }
    }
  }

  fun bulkUpdateVegetableSellingPrices(updates: Map<Long, Double>, onSuccess: () -> Unit = {}) {
    if (updates.isEmpty()) {
      onSuccess()
      return
    }
    launchSerializedSave {
      try {
        val count = repo.bulkUpdateSellingPrices(updates)
        if (count > 0) {
          val bn = uiState.value.preferences.useBengaliDigits
          _snackbarEvents.tryEmit(
            "সকালের দর আপডেট হয়েছে (${BusinessCalculator.formatNumber(count, bn)}টি)"
          )
        } else {
          _snackbarEvents.tryEmit("কোনো দরের পরিবর্তন পাওয়া যায়নি")
        }
        onSuccess()
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("দর আপডেট সংরক্ষণ করা যায়নি।")
      }
    }
  }

  fun adjustStock(vegId: Long, actualStockUnits: Long, reason: String, onSuccess: () -> Unit = {}) {
    if (actualStockUnits < 0L) {
      _snackbarEvents.tryEmit("বাস্তব স্টক ঋণাত্মক হতে পারে না।")
      return
    }
    launchSerializedSave {
      try {
        repo.adjustStock(vegId, actualStockUnits, reason)
        _snackbarEvents.tryEmit("বাস্তব স্টক সমন্বয় সম্পন্ন হয়েছে")
        onSuccess()
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("স্টক সমন্বয় করা যায়নি।")
      }
    }
  }

  fun recordWastage(
    vegId: Long,
    quantityUnits: Long,
    reason: WastageReason,
    notes: String,
    onSuccess: () -> Unit = {},
  ) {
    if (quantityUnits <= 0L) {
      _snackbarEvents.tryEmit("অপচয়ের পরিমাণ শূন্যের বেশি হতে হবে।")
      return
    }
    launchSerializedSave {
      try {
        repo.recordWastage(vegId, quantityUnits, reason, notes)
        _snackbarEvents.tryEmit("পচা/নষ্ট অপচয় সংরক্ষিত হয়েছে")
        onSuccess()
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("অপচয় সংরক্ষণ করা যায়নি।")
      }
    }
  }

  fun cancelWastage(wastageId: Long) {
    launchSerializedSave {
      try {
        repo.cancelWastage(wastageId)
        _snackbarEvents.tryEmit("অপচয় বাতিল হয়েছে ও স্টক ফেরত এসেছে")
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("অপচয় বাতিল করা যায়নি।")
      }
    }
  }

  fun saveExpense(
    existingId: Long?,
    category: ExpenseCategory,
    amount: Double,
    paymentMethod: PaymentMethod,
    note: String,
    timestamp: Long,
    onSuccess: () -> Unit = {},
  ) {
    if (!amount.isFinite() || amount <= 0.0) {
      _snackbarEvents.tryEmit("সঠিক টাকার পরিমাণ লিখুন (শূন্যের বেশি)।")
      return
    }
    launchSerializedSave {
      try {
        repo.saveExpense(
          existingId,
          category,
          BusinessCalculator.round2(amount),
          paymentMethod,
          note,
          timestamp,
        )
        _snackbarEvents.tryEmit("খরচ সংরক্ষিত হয়েছে")
        onSuccess()
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("খরচ সংরক্ষণ করা যায়নি।")
      }
    }
  }

  fun cancelExpense(expenseId: Long) {
    launchSerializedSave {
      try {
        repo.cancelExpense(expenseId)
        _snackbarEvents.tryEmit("খরচ বাতিল করা হয়েছে")
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("খরচ বাতিল করা যায়নি।")
      }
    }
  }

  suspend fun createPartyImmediate(
    name: String,
    phone: String,
    partyType: PartyType,
    notes: String = "",
  ): PartyEntity {
    val cleanName = name.trim()
    val id = repo.addParty(cleanName, phone, partyType, notes)
    return PartyEntity(
      id = id,
      name = cleanName,
      phone = phone.trim(),
      partyType = partyType,
      notes = notes.trim(),
    )
  }

  fun addParty(name: String, phone: String, partyType: PartyType, notes: String = "") {
    if (name.isBlank()) {
      _snackbarEvents.tryEmit("অনুগ্রহ করে নাম লিখুন।")
      return
    }
    viewModelScope.launch {
      try {
        repo.addParty(name, phone, partyType, notes)
        _snackbarEvents.tryEmit("${partyType.labelBn} যোগ করা হয়েছে")
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("তথ্য সংরক্ষণ করা যায়নি।")
      }
    }
  }

  fun recordDuePayment(
    party: PartyEntity,
    amount: Double,
    paymentMethod: PaymentMethod,
    note: String,
    onSuccess: () -> Unit = {},
  ) {
    if (!amount.isFinite() || amount <= 0.0) {
      _snackbarEvents.tryEmit("সঠিক টাকার পরিমাণ লিখুন (শূন্যের বেশি)।")
      return
    }
    val roundedAmount = BusinessCalculator.round2(amount)
    val currentBalances =
      if (party.partyType == PartyType.CUSTOMER) uiState.value.customerBalances
      else uiState.value.supplierBalances
    val maxDue =
      currentBalances.firstOrNull { it.party.id == party.id }?.balanceDue ?: Double.MAX_VALUE
    if (roundedAmount > maxDue + BusinessCalculator.DUE_TOLERANCE) {
      _snackbarEvents.tryEmit("পরিশোধিত টাকা মোট পাওনা/বিলের চেয়ে বেশি হতে পারে না।")
      return
    }
    launchSerializedSave {
      try {
        repo.recordDuePayment(party, roundedAmount, paymentMethod, note)
        _snackbarEvents.tryEmit(
          if (party.partyType == PartyType.CUSTOMER) "বাকি টাকা আদায় হয়েছে"
          else "পাইকারের দেনা পরিশোধ হয়েছে"
        )
        onSuccess()
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("বকেয়া হিসাব সংরক্ষণ করা যায়নি।")
      }
    }
  }

  fun cancelDuePayment(paymentId: Long) {
    launchSerializedSave {
      try {
        repo.cancelDuePayment(paymentId)
        _snackbarEvents.tryEmit("বকেয়া লেনদেন বাতিল হয়েছে")
      } catch (_: Exception) {
        _snackbarEvents.tryEmit("বকেয়া লেনদেন বাতিল করা যায়নি।")
      }
    }
  }

  fun exportBackup(uri: Uri) = backupCoordinator.exportBackup(uri)

  fun prepareRestoreFromUri(uri: Uri) = backupCoordinator.prepareRestoreFromUri(uri)

  fun dismissPendingRestore() = backupCoordinator.dismissPendingRestore()

  fun confirmRestore() = backupCoordinator.confirmRestore()

  companion object {
    const val MAX_PIN_ATTEMPTS = 5
    const val PIN_LOCKOUT_DURATION_MS = 30_000L
    const val PIN_RELOCK_GRACE_MS = 120_000L
    private const val STATE_STOP_TIMEOUT_MS = 5_000L
  }
}
