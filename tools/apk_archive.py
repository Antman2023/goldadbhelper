"""Copy APK entries with consistent ZIP path names before alignment/signing."""
import copy


def copy_apk_entries(source, destination):
    entries = []
    names = set()
    for entry in source.infolist():
        # Windows aapt2 can emit backslashes in asset paths. Appending with
        # zipfile rewrites only the central directory, leaving stale local names.
        name = entry.orig_filename.replace('\\', '/')
        if name in names:
            raise ValueError('Duplicate APK entry after path normalization: ' + name)
        names.add(name)
        entries.append((entry, name))
    for entry, name in entries:
        normalized = copy.copy(entry)
        normalized.filename = normalized.orig_filename = name
        destination.writestr(normalized, source.read(entry))
