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



    # --- InteractionFetched: fetch rate by channel (string FACET) ---
        # Added to surface how often interactions are being fetched and which channels
        # are driving the most read traffic, helping identify hotspots or unexpected spikes.
        widget_line {
          title  = "Interaction fetch rate by channel (per minute)"
          row    = 10
          column = 1
          width  = 8
          height = 3

          nrql_query {
            account_id = var.nr_account_id
            query      = "SELECT rate(count(*), 1 minute) AS 'fetches/min' FROM InteractionFetched FACET channel SINCE 1 hour ago TIMESERIES"
          }
        }

        # --- InteractionFetched: fetch rate by interactionId (string FACET) ---
        # Added to identify which specific interactions are fetched most frequently,
        # useful for detecting hot interactions that may warrant caching or optimisation.
        widget_line {
          title  = "Top fetched interactions by interactionId (per minute)"
          row    = 10
          column = 9
          width  = 4
          height = 3

          nrql_query {
            account_id = var.nr_account_id
            query      = "SELECT rate(count(*), 1 minute) AS 'fetches/min' FROM InteractionFetched FACET interactionId LIMIT 10 SINCE 1 hour ago TIMESERIES"
          }
        }

        # --- InteractionFetched: total fetch count billboard ---
        # Added as a quick at-a-glance KPI showing overall read volume in the last hour,
        # complementing the InteractionCreated total widget already on the dashboard.
        widget_billboard {
          title  = "Total interactions fetched (1 hour)"
          row    = 13
          column = 1
          width  = 4
          height = 3

          nrql_query {
            account_id = var.nr_account_id
            query      = "SELECT count(*) AS 'fetches' FROM InteractionFetched SINCE 1 hour ago"
          }
        }

        # --- InteractionFetched: unique interactions fetched per channel ---
        # Added to show the spread of distinct interactions accessed per channel,
        # helping distinguish broad read patterns from repeated access to a few records.
        widget_line {
          title  = "Unique interactions fetched per channel (per minute)"
          row    = 13
          column = 5
          width  = 8
          height = 3

          nrql_query {
            account_id = var.nr_account_id
            query      = "SELECT uniqueCount(interactionId) AS 'unique interactions' FROM InteractionFetched FACET channel SINCE 1 hour ago TIMESERIES"
          }
        }

  }
}