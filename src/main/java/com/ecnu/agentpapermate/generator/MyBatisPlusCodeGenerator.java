
package com.ecnu.agentpapermate.generator;

import cn.hutool.core.lang.Dict;
import cn.hutool.setting.yaml.YamlUtil;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.engine.VelocityTemplateEngine;

import java.util.Collections;
import java.util.Map;

public class MyBatisPlusCodeGenerator {

    // 需要生成的表名
    private static final String[] TABLE_NAMES = {
            "paper"
    };

    public static void main(String[] args) {

        // 获取数据源信息
        Dict dict = YamlUtil.loadByPath("application-dev.yml");
        Map<String, Object> dataSourceConfig =
                dict.getByPath("apm.datasource");

        String url = String.valueOf(dataSourceConfig.get("url"));
        String username = String.valueOf(dataSourceConfig.get("username"));
        String password = String.valueOf(dataSourceConfig.get("password"));

        // 项目根路径
        String projectPath = System.getProperty("user.dir");

        // 创建代码生成器
        FastAutoGenerator.create(url, username, password)

                // 全局配置
                .globalConfig(builder -> builder
                        .author("wolf_cub")
                        .outputDir(projectPath + "/src/main/java")
                        .disableOpenDir()
                        .commentDate("yyyy-MM-dd")
                )

                // 包配置
                .packageConfig(builder -> builder
                        .parent("com.ecnu.agentpapermate")
                        .entity("entity.model")
                        .mapper("mapper")
                        .service("service")
                        .serviceImpl("service.impl")
                        .controller("controller")
                        .xml("mapper")
                        // Mapper XML 单独生成到 resources/mapper
                        .pathInfo(Collections.singletonMap(
                                OutputFile.xml,
                                projectPath + "/src/main/resources/mapper"
                        ))
                )

                // 策略配置
                .strategyConfig(builder -> builder

                        // 指定生成哪些表
                        .addInclude(TABLE_NAMES)

                        // Entity 配置
                        .entityBuilder()
                        .enableLombok()
                        .enableTableFieldAnnotation()
                        // 雪花 ID
                        .idType(IdType.ASSIGN_ID)
                        // 逻辑删除
                        .logicDeleteColumnName("is_delete")

                        // Controller 配置
                        .controllerBuilder()
                        .enableRestStyle()

                        // Mapper 配置
                        .mapperBuilder()
                        .enableMapperAnnotation()
                        .enableBaseResultMap()

                        // Service 配置
                        .serviceBuilder()
                        .formatServiceFileName("%sService")
                        .formatServiceImplFileName("%sServiceImpl")
                )

                // 使用 Velocity 模板引擎
                .templateEngine(new VelocityTemplateEngine())

                // 执行生成
                .execute();
    }
}
