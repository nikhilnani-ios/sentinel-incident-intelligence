resource "aws_db_subnet_group" "main" {
  name       = "sentinel-${var.environment}"
  subnet_ids = aws_subnet.private[*].id
}

resource "random_password" "database" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "database" {
  name = "sentinel/${var.environment}/database"
}

resource "aws_secretsmanager_secret_version" "database" {
  secret_id = aws_secretsmanager_secret.database.id
  secret_string = jsonencode({
    username = "sentinel"
    password = random_password.database.result
    host     = aws_db_instance.main.address
    database = "sentinel"
  })
}

resource "aws_db_instance" "main" {
  identifier     = "sentinel-${var.environment}"
  engine         = "postgres"
  engine_version = "16.4"
  instance_class = var.database_instance_class

  allocated_storage     = 100
  max_allocated_storage = 500
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = "sentinel"
  username = "sentinel"
  password = random_password.database.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.platform.id]

  multi_az                = true
  backup_retention_period = 14
  backup_window           = "07:00-08:00"
  maintenance_window       = "sun:08:30-sun:09:30"

  # Incident data is operational history, not a system of record — but losing it mid-incident would
  # be extremely bad, so point-in-time recovery stays on.
  deletion_protection      = true
  skip_final_snapshot      = false
  final_snapshot_identifier = "sentinel-${var.environment}-final"

  performance_insights_enabled = true
  enabled_cloudwatch_logs_exports = ["postgresql"]

  auto_minor_version_upgrade = true
  apply_immediately          = false
}

resource "aws_elasticache_subnet_group" "main" {
  name       = "sentinel-${var.environment}"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_elasticache_replication_group" "main" {
  replication_group_id = "sentinel-${var.environment}"
  description          = "Deduplication windows, rate limit buckets and analytics cache"

  engine         = "redis"
  engine_version = "7.1"
  node_type      = "cache.t4g.small"

  num_cache_clusters         = 2
  automatic_failover_enabled = true
  multi_az_enabled           = true

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [aws_security_group.platform.id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true

  # Everything in Redis is reconstructible from Postgres and Kafka, so eviction under memory
  # pressure is preferable to refusing writes.
  parameter_group_name = "default.redis7"
  snapshot_retention_limit = 1
}

resource "aws_msk_cluster" "main" {
  cluster_name           = "sentinel-${var.environment}"
  kafka_version          = "3.7.x"
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type   = var.kafka_instance_type
    client_subnets  = aws_subnet.private[*].id
    security_groups = [aws_security_group.platform.id]

    storage_info {
      ebs_storage_info {
        volume_size = 200
      }
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  open_monitoring {
    prometheus {
      jmx_exporter { enabled_in_broker = true }
      node_exporter { enabled_in_broker = true }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.main.arn
    revision = aws_msk_configuration.main.latest_revision
  }
}

resource "aws_msk_configuration" "main" {
  name           = "sentinel-${var.environment}"
  kafka_versions = ["3.7.x"]

  server_properties = <<-PROPERTIES
    auto.create.topics.enable=false
    default.replication.factor=3
    min.insync.replicas=2
    num.partitions=12
    # Signal topics are a seven-day buffer, not an archive; the durable record lives in Postgres.
    log.retention.hours=168
    log.retention.bytes=-1
    unclean.leader.election.enable=false
  PROPERTIES
}
