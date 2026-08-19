package com.fooddelivery.contract;

import org.hibernate.boot.model.TypeContributions;
import org.hibernate.dialect.H2Dialect;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.jdbc.VarcharJdbcType;
import org.hibernate.type.descriptor.jdbc.spi.JdbcTypeRegistry;

public class CustomH2Dialect extends H2Dialect {
    @Override
    public void contributeTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
        super.contributeTypes(typeContributions, serviceRegistry);
        typeContributions.getTypeConfiguration().getDdlTypeRegistry().addDescriptor(
            new org.hibernate.type.descriptor.sql.internal.DdlTypeImpl(SqlTypes.NAMED_ENUM, "varchar", this)
        );
    }
}
