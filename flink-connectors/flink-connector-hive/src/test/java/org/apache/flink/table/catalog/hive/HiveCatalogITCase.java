/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.catalog.hive;

import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.connectors.hive.FlinkStandaloneHiveRunner;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.api.TableUtils;
import org.apache.flink.table.api.Types;
import org.apache.flink.table.api.java.BatchTableEnvironment;
import org.apache.flink.table.catalog.CatalogTable;
import org.apache.flink.table.catalog.CatalogTableBuilder;
import org.apache.flink.table.catalog.ObjectPath;
import org.apache.flink.table.descriptors.FileSystem;
import org.apache.flink.table.descriptors.FormatDescriptor;
import org.apache.flink.table.descriptors.OldCsv;
import org.apache.flink.types.Row;

import com.klarna.hiverunner.HiveShell;
import com.klarna.hiverunner.annotations.HiveSQL;
import org.apache.hadoop.hive.conf.HiveConf;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * IT case for HiveCatalog.
 * TODO: move to flink-connector-hive-test end-to-end test module once it's setup
 */
@RunWith(FlinkStandaloneHiveRunner.class)
public class HiveCatalogITCase {

	@HiveSQL(files = {})
	private static HiveShell hiveShell;

	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	private static HiveCatalog hiveCatalog;
	private static HiveConf hiveConf;

	private String sourceTableName = "csv_source";
	private String sinkTableName = "csv_sink";

	@BeforeClass
	public static void createCatalog() {
		hiveConf = hiveShell.getHiveConf();
		hiveCatalog = HiveTestUtils.createHiveCatalog(hiveConf);
		hiveCatalog.open();
	}

	@AfterClass
	public static void closeCatalog() {
		if (hiveCatalog != null) {
			hiveCatalog.close();
		}
	}

	@Test
	public void testCsvTableViaSQL() throws Exception {
		EnvironmentSettings settings = EnvironmentSettings.newInstance().useBlinkPlanner().inBatchMode().build();
		TableEnvironment tableEnv = TableEnvironment.create(settings);

		tableEnv.registerCatalog("myhive", hiveCatalog);
		tableEnv.useCatalog("myhive");

		String path = this.getClass().getResource("/csv/test.csv").getPath();

		tableEnv.sqlUpdate("create table test2 (name String, age Int) with (\n" +
			"   'connector.type' = 'filesystem',\n" +
			"   'connector.path' = 'file://" + path + "',\n" +
			"   'format.type' = 'csv'\n" +
			")");

		Table t = tableEnv.sqlQuery("SELECT * FROM myhive.`default`.test2");

		List<Row> result = TableUtils.collectToList(t);

		// assert query result
		assertEquals(
			new HashSet<>(Arrays.asList(
				Row.of("1", 1),
				Row.of("2", 2),
				Row.of("3", 3))),
			new HashSet<>(result)
		);

		tableEnv.sqlUpdate("ALTER TABLE test2 RENAME TO newtable");

		t = tableEnv.sqlQuery("SELECT * FROM myhive.`default`.newtable");

		result = TableUtils.collectToList(t);

		// assert query result
		assertEquals(
			new HashSet<>(Arrays.asList(
				Row.of("1", 1),
				Row.of("2", 2),
				Row.of("3", 3))),
			new HashSet<>(result)
		);

		tableEnv.sqlUpdate("DROP TABLE newtable");
	}

	@Test
	public void testCsvTableViaAPI() throws Exception {
		ExecutionEnvironment execEnv = ExecutionEnvironment.createLocalEnvironment(1);
		BatchTableEnvironment tableEnv = BatchTableEnvironment.create(execEnv);

		tableEnv.registerCatalog("myhive", hiveCatalog);
		tableEnv.useCatalog("myhive");

		TableSchema schema = TableSchema.builder()
			.field("name", DataTypes.STRING())
			.field("age", DataTypes.INT())
			.build();

		FormatDescriptor format = new OldCsv()
			.field("name", Types.STRING())
			.field("age", Types.INT());

		CatalogTable source =
			new CatalogTableBuilder(
				new FileSystem().path(this.getClass().getResource("/csv/test.csv").getPath()),
				schema)
			.withFormat(format)
			.inAppendMode()
			.withComment("Comment.")
			.build();

		Path p = Paths.get(tempFolder.newFolder().getAbsolutePath(), "test.csv");

		CatalogTable sink =
			new CatalogTableBuilder(
				new FileSystem().path(p.toAbsolutePath().toString()),
				schema)
				.withFormat(format)
				.inAppendMode()
				.withComment("Comment.")
				.build();

		hiveCatalog.createTable(
			new ObjectPath(HiveCatalog.DEFAULT_DB, sourceTableName),
			source,
			false
		);

		hiveCatalog.createTable(
			new ObjectPath(HiveCatalog.DEFAULT_DB, sinkTableName),
			sink,
			false
		);

		Table t = tableEnv.sqlQuery(
			String.format("select * from myhive.`default`.%s", sourceTableName));

		List<Row> result = tableEnv.toDataSet(t, Row.class).collect();

		// assert query result
		assertEquals(
			Arrays.asList(
				Row.of("1", 1),
				Row.of("2", 2),
				Row.of("3", 3)),
			result
		);

		tableEnv.sqlUpdate(
			String.format("insert into myhive.`default`.%s select * from myhive.`default`.%s",
				sinkTableName,
				sourceTableName));
		tableEnv.execute("myjob");

		// assert written result
		File resultFile = new File(p.toAbsolutePath().toString());
		BufferedReader reader = new BufferedReader(new FileReader(resultFile));
		String readLine;
		for (int i = 0; i < 3; i++) {
			readLine = reader.readLine();
			assertEquals(String.format("%d,%d", i + 1, i + 1), readLine);
		}

		// No more line
		assertNull(reader.readLine());

		tableEnv.sqlUpdate(String.format("DROP TABLE %s", sourceTableName));
		tableEnv.sqlUpdate(String.format("DROP TABLE %s", sinkTableName));
	}
}
