package com.wbrawner.budgetserver.transaction

import com.wbrawner.budgetserver.ErrorResponse
import com.wbrawner.budgetserver.budget.BudgetRepository
import com.wbrawner.budgetserver.category.Category
import com.wbrawner.budgetserver.category.CategoryRepository
import com.wbrawner.budgetserver.getCurrentUser
import com.wbrawner.budgetserver.permission.UserPermissionRepository
import com.wbrawner.budgetserver.setToEndOfMonth
import com.wbrawner.budgetserver.setToFirstOfMonth
import io.swagger.annotations.Api
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.Authorization
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.lang.Integer.min
import java.time.Instant
import java.util.*
import javax.transaction.Transactional

@RestController
@RequestMapping("/transactions")
@Api(value = "Transactions", tags = ["Transactions"], authorizations = [Authorization("basic")])
@Transactional
open class TransactionController(
        private val budgetRepository: BudgetRepository,
        private val categoryRepository: CategoryRepository,
        private val transactionRepository: TransactionRepository,
        private val userPermissionsRepository: UserPermissionRepository
) {
    private val logger = LoggerFactory.getLogger(TransactionController::class.java)

    @GetMapping("", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ApiOperation(value = "getTransactions", nickname = "getTransactions", tags = ["Transactions"])
    open fun getTransactions(
            @RequestParam("categoryId") categoryIds: Array<Long>? = null,
            @RequestParam("budgetId") budgetIds: Array<Long>? = null,
            @RequestParam("from") from: String? = null,
            @RequestParam("to") to: String? = null,
            @RequestParam count: Int?,
            @RequestParam page: Int?,
            @RequestParam sortBy: String?,
            @RequestParam sortOrder: Sort.Direction?
    ): ResponseEntity<List<TransactionResponse>> {
        val budgets = if (budgetIds != null) {
            userPermissionsRepository.findAllByUserAndBudget_IdIn(
                    user = getCurrentUser()!!,
                    budgetIds = budgetIds.toList(),
                    pageable = PageRequest.of(page ?: 0, count ?: 1000))
        } else {
            userPermissionsRepository.findAllByUser(getCurrentUser()!!, null)
        }.mapNotNull {
            it.budget
        }
        val categories = if (categoryIds?.isNotEmpty() == true) {
            categoryRepository.findAllByBudgetInAndIdIn(budgets, categoryIds.toList())
        } else {
            categoryRepository.findAllByBudgetIn(budgets)
        }
        val pageRequest = PageRequest.of(
                min(0, page?.minus(1) ?: 0),
                count ?: 1000,
                sortOrder ?: Sort.Direction.DESC,
                sortBy ?: "date"
        )
        val fromInstant = try {
            Instant.parse(from!!)
        } catch (ignored: NullPointerException) {
            null
        } catch (e: Exception) {
            logger.error("Failed to parse $to to Instant for 'from' parameter", e)
            null
        }
        val toInstant = try {
            Instant.parse(to!!)
        } catch (ignored: NullPointerException) {
            null
        } catch (e: Exception) {
            logger.error("Failed to parse $to to Instant for 'to' parameter", e)
            null
        }
        val transactions = transactionRepository.findAllByBudgetInAndCategoryInAndDateGreaterThanAndDateLessThan(
                budgets,
                categories,
                fromInstant ?: GregorianCalendar().setToFirstOfMonth().toInstant(),
                toInstant ?: GregorianCalendar().setToEndOfMonth().toInstant(),
                pageRequest
        ).map { TransactionResponse(it) }
        return ResponseEntity.ok(transactions)
    }

    @GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ApiOperation(value = "getTransaction", nickname = "getTransaction", tags = ["Transactions"])
    open fun getTransaction(@PathVariable id: Long): ResponseEntity<TransactionResponse> {
        val budgets = userPermissionsRepository.findAllByUser(getCurrentUser()!!, null)
                .mapNotNull {
                    it.budget
                }
        val transaction = transactionRepository.findAllByIdAndBudgetIn(id, budgets).firstOrNull()
                ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(TransactionResponse(transaction))
    }

    @PostMapping("/new", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @ApiOperation(value = "newTransaction", nickname = "newTransaction", tags = ["Transactions"])
    open fun newTransaction(@RequestBody request: NewTransactionRequest): ResponseEntity<Any> {
        val budget = userPermissionsRepository.findAllByUserAndBudget_Id(getCurrentUser()!!, request.budgetId, null)
                .firstOrNull()
                ?.budget
                ?: return ResponseEntity.badRequest().body(ErrorResponse("Invalid budget ID"))
        val category: Category? = request.categoryId?.let {
            categoryRepository.findByBudgetAndId(budget, request.categoryId).orElse(null)
        }
        return ResponseEntity.ok(TransactionResponse(transactionRepository.save(Transaction(
                title = request.title,
                description = request.description,
                date = Instant.parse(request.date),
                amount = request.amount,
                category = category,
                expense = request.expense,
                budget = budget,
                createdBy = getCurrentUser()!!
        ))))
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    @ApiOperation(value = "updateTransaction", nickname = "updateTransaction", tags = ["Transactions"])
    open fun updateTransaction(@PathVariable id: Long, @RequestBody request: UpdateTransactionRequest): ResponseEntity<TransactionResponse> {
        var transaction = transactionRepository.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        var budget = userPermissionsRepository.findAllByUserAndBudget_Id(getCurrentUser()!!, transaction.budget!!.id!!, null)
                .firstOrNull()
                ?.budget
                ?: return ResponseEntity.notFound().build()
        request.title?.let { transaction = transaction.copy(title = it) }
        request.description?.let { transaction = transaction.copy(description = it) }
        request.date?.let { transaction = transaction.copy(date = Instant.parse(it)) }
        request.amount?.let { transaction = transaction.copy(amount = it) }
        request.expense?.let { transaction = transaction.copy(expense = it) }
        request.budgetId?.let { budgetId ->
            userPermissionsRepository.findAllByUserAndBudget_Id(getCurrentUser()!!, budgetId, null)
                    .firstOrNull()
                    ?.budget
                    ?.let {
                        budget = it
                        transaction = transaction.copy(budget = it, category = null)
                    }
        }
        request.categoryId?.let {
            categoryRepository.findByBudgetAndId(budget, it).orElse(null)?.let { category ->
                transaction = transaction.copy(category = category)
            }
        }
        return ResponseEntity.ok(TransactionResponse(transactionRepository.save(transaction)))
    }

    @DeleteMapping("/{id}", produces = [MediaType.TEXT_PLAIN_VALUE])
    @ApiOperation(value = "deleteTransaction", nickname = "deleteTransaction", tags = ["Transactions"])
    open fun deleteTransaction(@PathVariable id: Long): ResponseEntity<Unit> {
        val transaction = transactionRepository.findById(id).orElse(null) ?: return ResponseEntity.notFound().build()
        // Check that the transaction belongs to an budget that the user has access to before deleting it
        userPermissionsRepository.findAllByUserAndBudget_Id(getCurrentUser()!!, transaction.budget!!.id!!, null)
                .firstOrNull()
                ?.budget
                ?: return ResponseEntity.notFound().build()
        transactionRepository.delete(transaction)
        return ResponseEntity.ok().build()
    }
}