variable "region" {
  description = "AWS region"
  type        = string
  default     = "us-east-2"
}

variable "environment" {
  description = "Environment name, used in resource names and tags"
  type        = string
  default     = "production"
}

variable "vpc_cidr" {
  type    = string
  default = "10.40.0.0/16"
}

variable "database_instance_class" {
  description = "RDS instance class. db.t4g.medium is the smallest that holds a realistic incident volume comfortably."
  type        = string
  default     = "db.t4g.medium"
}

variable "kafka_instance_type" {
  type    = string
  default = "kafka.m7g.large"
}

variable "eks_node_instance_types" {
  type    = list(string)
  default = ["m7g.large"]
}
