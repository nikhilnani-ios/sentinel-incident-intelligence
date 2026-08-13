.DEFAULT_GOAL := help
SHELL := /bin/bash

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'

doctor: ## Check local prerequisites before starting the stack
	./scripts/doctor.sh

up: ## Start the whole stack (infrastructure, services, web, observability)
	docker compose up -d --build
	@echo "Web        http://localhost:3000"
	@echo "Grafana    http://localhost:3001"
	@echo "Prometheus http://localhost:9090"

down: ## Stop the stack, keeping volumes
	docker compose down

reset: ## Stop the stack and delete all data
	docker compose down -v

logs: ## Tail service logs
	docker compose logs -f ingest-service correlation-service incident-service insight-service

test: ## Run the service test suite
	cd services && mvn -B verify

test-web: ## Typecheck and lint the web app
	cd web && npm run typecheck && npm run lint

build: ## Build all service jars
	cd services && mvn -B -DskipTests package

seed: ## Fire a realistic incident scenario at the running stack
	./scripts/simulate.sh cascade

storm: ## Fire a high-volume signal storm to exercise dedup and rate limiting
	./scripts/simulate.sh storm

psql: ## Open a psql shell against the local database
	docker compose exec postgres psql -U sentinel -d sentinel

topics: ## List Kafka topics and their partitions
	docker compose exec kafka kafka-topics --bootstrap-server kafka:29092 --describe

.PHONY: help doctor up down reset logs test test-web build seed storm psql topics
