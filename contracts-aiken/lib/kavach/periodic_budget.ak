//// Port of the Java `PeriodicBudgetLib`: the optional core budget envelope and its counter.

use aiken/builtin
use aiken/interval.{Finite}
use cardano/transaction.{Transaction}
use kavach/account_lib.{shape, uint63}
use kavach/types.{AccountId}

/// `budget-configuration = #6.121([schema-version, budget / nothing, authorization])`
pub type Configuration {
  schema_version: Int,
  budget: Option<Budget>,
  authorization: Data,
}

/// `budget = #6.121([counter: account-id, period, limit])`
pub type Budget {
  counter: AccountId,
  period: Int,
  limit: Int,
}

/// `usage = #6.121([schema-version, period, window-start, spent])`
pub type Usage {
  schema_version: Int,
  period: Int,
  window_start: Int,
  spent: Int,
}

/// Mirrors `PeriodicBudgetLib.initial`.
pub fn initial() -> Usage {
  Usage { schema_version: 1, period: 0, window_start: 0, spent: 0 }
}

/// Mirrors `PeriodicBudgetLib.isProfile`.
pub fn is_profile(data: Data) -> Bool {
  shape(data, 0, 3)
}

/// Mirrors `PeriodicBudgetLib.enabled`.
pub fn enabled(data: Data) -> Bool {
  if !is_profile(data) {
    False
  } else {
    expect configuration: Configuration = data
    when configuration.budget is {
      Some(budget) -> 0 < budget.limit
      None -> False
    }
  }
}

/// Mirrors `PeriodicBudgetLib.budgetShape`.
pub fn budget_shape(budget: Budget) -> Bool {
  shape(budget, 0, 3) && shape(budget.counter, 0, 2) && builtin.length_of_bytearray(
    budget.counter.policy,
  ) == 28 && builtin.length_of_bytearray(budget.counter.name) == 0 && (
    budget.period == 1 || budget.period == 2
  ) && uint63(budget.limit)
}

/// Mirrors `PeriodicBudgetLib.configuration`.
pub fn configuration(configuration: Configuration) -> Bool {
  if !is_profile(configuration) || configuration.schema_version != 1 || builtin.length_of_bytearray(
    builtin.serialise_data(configuration),
  ) > 1024 {
    False
  } else {
    let budget_data: Data = configuration.budget
    when configuration.budget is {
      None -> {
        let expected: Data = None
        budget_data == expected
      }
      Some(budget) -> {
        let expected: Data = Some(budget)
        budget_data == expected && budget_shape(budget)
      }
    }
  }
}

/// Mirrors `PeriodicBudgetLib.usageShape`.
pub fn usage_shape(usage: Usage) -> Bool {
  shape(usage, 0, 4) && usage.schema_version == 1 && uint63(usage.window_start) && uint63(
    usage.spent,
  ) && if usage.period == 0 {
    usage.window_start == 0 && usage.spent == 0
  } else {
    usage.period == 1 || usage.period == 2
  }
}

/// Mirrors `PeriodicBudgetLib.duration`.
pub fn duration(period: Int) -> Int {
  if period == 1 {
    86400000
  } else if period == 2 {
    604800000
  } else {
    fail @"unsupported budget period"
  }
}

/// Mirrors `PeriodicBudgetLib.window`.
pub fn window(timestamp: Int, period: Int) -> Int {
  let anchor =
    if period == 2 {
      345600000
    } else {
      0
    }
  let length = duration(period)
  if !uint63(timestamp) || timestamp < anchor {
    fail @"timestamp precedes the budget window anchor"
  } else {
    anchor + ( timestamp - anchor ) / length * length
  }
}

/// Mirrors `PeriodicBudgetLib.successor`.
pub fn successor(previous: Usage, budget: Budget, debit: Int, tx: Transaction) -> Usage {
  if !usage_shape(previous) || !budget_shape(budget) || !uint63(debit) {
    fail @"malformed usage, budget or debit"
  } else {
    let range = tx.validity_range
    let lower =
      when range.lower_bound.bound_type is {
        Finite(time) -> time
        _ -> -1
      }
    let upper =
      when range.upper_bound.bound_type is {
        Finite(time) -> time
        _ -> -1
      }
    let start = window(lower, budget.period)
    let end = start + duration(budget.period)
    if !uint63(upper) || lower >= upper || previous.window_start > lower || upper > end || upper == end && range.upper_bound.is_inclusive {
      fail @"transaction interval leaves the budget window"
    } else {
      let carried =
        if previous.period == budget.period && previous.window_start == start {
          previous.spent
        } else {
          0
        }
      let total = carried + debit
      if !uint63(total) || total > budget.limit {
        fail @"budget limit exceeded"
      } else {
        Usage {
          schema_version: 1,
          period: budget.period,
          window_start: start,
          spent: total,
        }
      }
    }
  }
}
