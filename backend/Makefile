.PHONY: build-FridayFunction

build-FridayFunction:
	npm run build
	test -s dist/handler.js
	mkdir -p "$(ARTIFACTS_DIR)"
	cp dist/handler.js "$(ARTIFACTS_DIR)/handler.js"
