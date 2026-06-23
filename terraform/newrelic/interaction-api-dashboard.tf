terraform {
  required_providers {
    newrelic = {
      source  = "newrelic/newrelic"
      version = "~> 3.0"
    }
  }
}

provider "newrelic" {
  account_id = var.nr_account_id
  api_key    = var.nr_api_key
  region     = "US"
}

variable "nr_account_id" {
  description = "NewRelic account ID"
  type        = number
}

variable "nr_api_key" {
  description = "NewRelic User API key (for Terraform provider auth)"
  type        = string
  sensitive   = true
}

resource "newrelic_one_dashboard" "interaction_api" {
  name        = "Interaction API — Business KPIs"
  permissions = "public_read_only"

  page {
    name = "Overview"

    # --- Total interactions (billboard) ---
    widget_billboard {
      title  = "Total interactions (1 hour)"
      row    = 1
      column = 1
      width  = 4
      height = 3

      nrql_query {
        account_id = var.nr_account_id
        query      = "SELECT count(*) AS 'interactions' FROM InteractionCreated SINCE 1 hour ago"
      }
    }

  }
}
