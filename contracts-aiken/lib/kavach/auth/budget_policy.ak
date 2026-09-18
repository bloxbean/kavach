//// Port of the Java `BudgetPolicyLib`: decodes the core budget envelope before the module
//// validates its authorization payload.

use kavach/auth/policy.{PolicyConfig}
use kavach/periodic_budget.{Configuration}

/// Rejects malformed or unusable core budget configuration before accepting new-key
/// possession. Mirrors `BudgetPolicyLib.authorization`.
pub fn authorization(data: Data) -> PolicyConfig {
  expect configuration: Configuration = data
  if !periodic_budget.configuration(configuration) {
    fail @"malformed budget configuration"
  } else {
    expect config: PolicyConfig = configuration.authorization
    config
  }
}
