FROM scratch
COPY rootfs/ /
LABEL org.fieldnotes.component="app" org.fieldnotes.runtime="1"
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/field-notes-server"]
