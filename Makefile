.PHONY: up up-2 down reset build test test-int migrate seed gen-clients new-module

up:
	@echo "up command not yet implemented"

up-2:
	@echo "up-2 command not yet implemented"

down:
	@echo "down command not yet implemented"

reset:
	@echo "reset command not yet implemented"

build:
	@echo "build command not yet implemented"

test:
	@echo "test command not yet implemented"

test-int:
	@echo "test-int command not yet implemented"

migrate:
	@echo "migrate command not yet implemented"

seed:
	@echo "seed command not yet implemented"

gen-clients:
	@echo "gen-clients command not yet implemented"

new-module:
	@if [ -z "$(NAME)" ] || [ -z "$(SCHEMA)" ]; then \
		echo "Usage: make new-module NAME=m2catalogue SCHEMA=catalogue"; \
	else \
		python tools/new_module.py --name $(NAME) --schema $(SCHEMA); \
	fi
