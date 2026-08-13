terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }

  # Remote state with locking. Local state is fine until the second person runs apply.
  backend "s3" {
    bucket         = "sentinel-terraform-state"
    key            = "platform/terraform.tfstate"
    region         = "us-east-2"
    dynamodb_table = "sentinel-terraform-locks"
    encrypt        = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Platform    = "sentinel"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
