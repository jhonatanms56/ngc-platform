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



    # --- InteractionFetched: fetch rate by channel (string facet) ---
        # Added to surface how often interactions are fetched and which channels drive the most traffic
        widget_line {
          title  = "Interaction fetch rate by channel (per minute)"
          row    = 10
          column = 1
          width  = 6
          height = 3

          nrql_query {
            account_id = var.nr_account_id
            query      = "SELECT rate(count(*), 1 minute) FROM InteractionFetched FACET channel SINCE 1 hour ago TIMESERIES"
          }
        }

        # --- InteractionFetched: fetch rate by interactionId (string facet) ---
        # Added to identify which specific interactions are fetched most frequently, helping detect hot-spots
        widget_line {
          title  = "Interaction fetch rate by interactionId (per minute)"
          row    = 10
          column = 7
          width  = 6
          height = 3

          nrql_query {
            account_id = var.nr_account_id
            query      = "SELECT rate(count(*), 1 minute) FROM InteractionFetched FACET interactionId SINCE 1 hour ago TIMESERIES"
          }
        }

  }
}