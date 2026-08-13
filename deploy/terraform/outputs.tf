output "cluster_name" {
  value = aws_eks_cluster.main.name
}

output "cluster_endpoint" {
  value = aws_eks_cluster.main.endpoint
}

output "database_endpoint" {
  value     = aws_db_instance.main.endpoint
  sensitive = true
}

output "database_secret_arn" {
  description = "Read by External Secrets to populate the sentinel-secrets Kubernetes secret"
  value       = aws_secretsmanager_secret.database.arn
}

output "redis_endpoint" {
  value     = aws_elasticache_replication_group.main.primary_endpoint_address
  sensitive = true
}

output "kafka_bootstrap_brokers" {
  value     = aws_msk_cluster.main.bootstrap_brokers_tls
  sensitive = true
}
