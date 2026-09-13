.PHONY: up down logs test burst clean

up:
	docker compose up --build -d

down:
	docker compose down

logs:
	docker compose logs -f app

test:
	mvn test

burst:
	python3 scripts/burst.py all

clean:
	docker compose down -v --remove-orphans
