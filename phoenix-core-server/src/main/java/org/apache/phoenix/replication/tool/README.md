# Phoenix Replication Log File Analyzer

A command-line tool for analyzing Phoenix Replication Log files. This tool can read a log file or a
directory of log files, print the header, trailer (if present), and block headers, optionally
decode and print the LogRecord contents in a human-readable format, verify checksums for each
block, and report any corruption or format violations.

## Usage

```bash
hadoop jar phoenix-server.jar org.apache.phoenix.replication.tool.LogFileAnalyzer [options]
    <log-file-or-directory>
```

### Options

- `-h, --help`: Print help message
- `-v, --verbose`: Print verbose output including block headers
- `-c, --check`: Verify checksums and report any corruption
- `-d, --decode`: Decode and print LogRecord contents in human-readable format

### Examples

1. Basic analysis of a log file:
```bash
hadoop jar phoenix-server.jar org.apache.phoenix.replication.tool.LogFileAnalyzer /my/log.plog
```

2. Analyze with record decoding:
```bash
hadoop jar phoenix-server.jar org.apache.phoenix.replication.tool.LogFileAnalyzer -d /my/log.plog
```

3. Verify checksums and report corruption:
```bash
hadoop jar phoenix-server.jar org.apache.phoenix.replication.tool.LogFileAnalyzer -c /my/log.plog
```

4. Analyze all log files in a directory with verbose output:
```bash
hadoop jar phoenix-server.jar org.apache.phoenix.replication.tool.LogFileAnalyzer -v /my/logs/
```

## Output Format

The tool outputs information in the following format:

1. File Information:
   - File path
   - File size
   - Last modified time

2. Header Information:
   - Version

3. Block Information (if verbose):
   - Block number
   - Block size
   - Block offset
   - Checksum

4. Record Information (if decode option is used):
   - Record number
   - Table name
   - Commit ID
   - Mutation type (PUT/DELETE)
   - Row key
   - Timestamp
   - Column information

5. Trailer Information:
   - Record count
   - Block count
   - Block start offset
   - Trailer start offset

6. Verification Results (if check option is used):
   - Records read
   - Blocks read
   - Checksum verification status
   - Any corruption or format violations found

## Error Handling

The tool handles various error conditions:

1. Invalid file path or directory
2. Corrupted log files
3. Invalid log file format
4. Checksum mismatches
5. Missing or invalid headers/trailers

When errors are encountered, the tool will:
1. Print detailed error messages
2. Continue processing other files in case of directory analysis
3. Return appropriate exit codes:
   - 0: Success
   - 1: Error in command-line arguments
   - 2: Error reading/analyzing files
   - 3: Corruption detected (when using -c option)
