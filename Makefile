develop:
	clojure -M:nrepl
.PHONY: develop

release:
	clojure -T:build uber
	cat build/stub.sh target/stampdf.jar > target/stampdf
	chmod a+x target/stampdf
.PHONY: release
