/**
 * Copyright 2013 Cloudera Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kitesdk.data;

import com.google.common.collect.ImmutableList;
import org.kitesdk.data.partition.PartitionFunctions;
import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import java.util.Locale;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import org.apache.avro.generic.GenericRecord;

import org.kitesdk.data.impl.Accessor;
import org.kitesdk.data.partition.HashFieldPartitioner;
import org.kitesdk.data.partition.IdentityFieldPartitioner;
import org.kitesdk.data.partition.IntRangeFieldPartitioner;
import org.kitesdk.data.partition.RangeFieldPartitioner;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;

/**
 * <p>
 * The strategy used to determine how a dataset is partitioned.
 * </p>
 * <p>
 * A {@code PartitionStrategy} is configured with one or more
 * {@link FieldPartitioner}s upon creation. When a {@link Dataset} is configured
 * with a partition strategy, we say that data is partitioned. Any entities
 * written to a partitioned dataset are evaluated with its
 * {@code PartitionStrategy} which, in turn, produces a {@link PartitionKey}
 * that is used by the dataset implementation to select the proper partition.
 * </p>
 * <p>
 * Users should use the inner {@link Builder} to create new instances.
 * </p>
 * 
 * @see FieldPartitioner
 * @see PartitionKey
 * @see DatasetDescriptor
 * @see Dataset
 */
@Immutable
public class PartitionStrategy {

  private final List<FieldPartitioner> fieldPartitioners;

  static {
    Accessor.setDefault(new AccessorImpl());
  }

  /**
   * Construct a partition strategy with a list of field partitioners.
   *
   * @deprecated will be removed in 0.12.0; use PartitionStrategy.Builder
   */
  @Deprecated
  public PartitionStrategy(List<FieldPartitioner> partitioners) {
    this.fieldPartitioners = ImmutableList.copyOf(partitioners);
  }

  /**
   * <p>
   * Get the list of field partitioners used for partitioning.
   * </p>
   * <p>
   * {@link FieldPartitioner}s are returned in the same order they are used
   * during partition selection.
   * </p>
   */
  public List<FieldPartitioner> getFieldPartitioners() {
    return fieldPartitioners;
  }

  /**
   * <p>
   * Return the cardinality produced by the contained field partitioners.
   * </p>
   * <p>
   * This can be used to aid in calculating resource usage used during certain
   * operations. For example, when writing data to a partitioned dataset, this
   * method can be used to estimate (or discover exactly, depending on the
   * partition functions) how many leaf partitions exist.
   * </p>
   * <p>
   * <strong>Warning:</strong> This method is allowed to lie and should be
   * treated only as a hint. Some partition functions are fixed (e.g. hash
   * modulo number of buckets), while others are open-ended (e.g. discrete
   * value) and depend on the input data.
   * </p>
   * 
   * @return The estimated (or possibly concrete) number of leaf partitions.
   */
  public int getCardinality() {
    int cardinality = 1;
    for (FieldPartitioner fieldPartitioner : fieldPartitioners) {
      cardinality *= fieldPartitioner.getCardinality();
    }
    return cardinality;
  }

  /**
   * <p>
   * Construct a partition key with a variadic array of values corresponding to
   * the field partitioners in this partition strategy.
   * </p>
   * <p>
   * It is permitted to have fewer values than field partitioners, in which case
   * all subpartititions in the unspecified parts of the key are matched by the
   * key.
   * </p>
   * <p>
   * Null values are not permitted.
   * </p>
   */
  public PartitionKey partitionKey(Object... values) {
    return new PartitionKey(values);
  }

  /**
   * <p>
   * Construct a partition key for the given entity.
   * </p>
   * <p>
   * This is a convenient way to find the partition that a given entity would be
   * written to, or to find a partition using objects from the entity domain.
   * </p>
   */
  public PartitionKey partitionKeyForEntity(Object entity) {
    return partitionKeyForEntity(entity, null);
  }

  /**
   * <p>
   * Construct a partition key for the given entity, reusing the supplied key if not
   * null.
   * </p>
   * <p>
   * This is a convenient way to find the partition that a given entity would be
   * written to, or to find a partition using objects from the entity domain.
   * </p>
   */
  @SuppressWarnings("unchecked")
  public PartitionKey partitionKeyForEntity(Object entity,
      @Nullable PartitionKey reuseKey) {
    PartitionKey key = (reuseKey == null ? newKey() : reuseKey);

    for (int i = 0; i < fieldPartitioners.size(); i++) {
      FieldPartitioner fp = fieldPartitioners.get(i);
      String name = fp.getSourceName();
      Object value;
      if (entity instanceof GenericRecord) {
        value = ((GenericRecord) entity).get(name);
      } else {
        try {
          PropertyDescriptor propertyDescriptor = new PropertyDescriptor(name,
              entity.getClass(), getter(name), null /* assume read only */);
          value = propertyDescriptor.getReadMethod().invoke(entity);
        } catch (IllegalAccessException e) {
          throw new RuntimeException("Cannot read property " + name + " from "
              + entity, e);
        } catch (InvocationTargetException e) {
          throw new RuntimeException("Cannot read property " + name + " from "
              + entity, e);
        } catch (IntrospectionException e) {
          throw new RuntimeException("Cannot read property " + name + " from "
              + entity, e);
        }
      }
      key.set(i, fp.apply(value));
    }
    return key;
  }

  /**
   * Constructs a new {@code PartitionKey} that can be used with this
   * {@code PartitionStrategy}.
   *
   * @return a new {@code PartitionKey}
   */
  private PartitionKey newKey() {
    return new PartitionKey(fieldPartitioners.size());
  }

  private String getter(String name) {
    return "get" + name.substring(0, 1).toUpperCase(Locale.ENGLISH) + name.substring(1);
  }

  /**
   * Return a {@link PartitionStrategy} for subpartitions starting at the given
   * index.
   */
  PartitionStrategy getSubpartitionStrategy(int startIndex) {
    if (startIndex == 0) {
      return this;
    }
    if (startIndex >= fieldPartitioners.size()) {
      return null;
    }
    return new PartitionStrategy(fieldPartitioners.subList(startIndex,
        fieldPartitioners.size()));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || !getClass().equals(o.getClass())) {
      return false;
    }
    PartitionStrategy that = (PartitionStrategy) o;
    return Objects.equal(this.fieldPartitioners, that.fieldPartitioners);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(fieldPartitioners);
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
        .add("fieldPartitioners", fieldPartitioners).toString();
  }

  /**
   * A fluent builder to aid in the construction of {@link PartitionStrategy}s.
   */
  public static class Builder {

    private List<FieldPartitioner> fieldPartitioners = Lists.newArrayList();

    /**
     * Configure a hash partitioner with the specified number of {@code buckets}
     * .
     *
     * @param name
     *          The entity field name from which to get values to be
     *          partitioned.
     * @param buckets
     *          The number of buckets into which data is to be partitioned.
     * @return An instance of the builder for method chaining.
     */
    public Builder hash(String name, int buckets) {
      fieldPartitioners.add(new HashFieldPartitioner(name, buckets));
      return this;
    }

    /**
     * Configure a hash partitioner with the specified number of {@code buckets}
     * .
     *
     * @param sourceName
     *          The entity field name from which to get values to be
     *          partitioned.
     * @param name
     *          The entity field name of the partition.
     * @param buckets
     *          The number of buckets into which data is to be partitioned.
     * @return An instance of the builder for method chaining.
     * @since 0.3.0
     */
    public Builder hash(String sourceName, String name, int buckets) {
      fieldPartitioners.add(new HashFieldPartitioner(sourceName, name, buckets));
      return this;
    }

    /**
     * Configure an identity partitioner for strings with a cardinality hint of
     * {@code buckets} size.
     * 
     * @param name
     *          The entity field name from which to get values to be
     *          partitioned.
     * @param buckets
     *          A hint as to the number of partitions that will be created (i.e.
     *          the number of discrete values for the field {@code name} in the
     *          data).
     * @return An instance of the builder for method chaining.
     * @see IdentityFieldPartitioner
     * @deprecated Use {@link #identity(String, Class, int)}.
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public Builder identity(String name, int buckets) {
      fieldPartitioners.add(new IdentityFieldPartitioner(name, String.class, buckets));
      return this;
    }

    /**
     * Configure an identity partitioner for a given type with a cardinality hint of
     * {@code buckets} size.
     *
     * @param name
     *          The entity field name from which to get values to be
     *          partitioned.
     * @param type
     *          The type of the field. This must match the schema.
     * @param buckets
     *          A hint as to the number of partitions that will be created (i.e.
     *          the number of discrete values for the field {@code name} in the
     *          data).
     * @return An instance of the builder for method chaining.
     * @see IdentityFieldPartitioner
     * @since 0.8.0
     */
    @SuppressWarnings("unchecked")
    public <S> Builder identity(String name, Class<S> type, int buckets) {
      fieldPartitioners.add(new IdentityFieldPartitioner(name, type, buckets));
      return this;
    }

    /**
     * Configure a range partitioner with a set of {@code upperBounds}.
     * 
     * @param name
     *          The entity field name from which to get values to be
     *          partitioned.
     * @param upperBounds
     *          A variadic list of upper bounds of each partition.
     * @return An instance of the builder for method chaining.
     * @see IntRangeFieldPartitioner
     */
    public Builder range(String name, int... upperBounds) {
      fieldPartitioners.add(new IntRangeFieldPartitioner(name, upperBounds));
      return this;
    }

    /**
     * Configure a range partitioner for strings with a set of {@code upperBounds}.
     * 
     * @param name
     *          The entity field name from which to get values to be
     *          partitioned.
     * @param upperBounds
     *          A variadic list of upper bounds of each partition.
     * @return An instance of the builder for method chaining.
     * @see org.kitesdk.data.partition.RangeFieldPartitioner
     */
    public Builder range(String name, String... upperBounds) {
      fieldPartitioners.add(new RangeFieldPartitioner(name, upperBounds));
      return this;
    }

    /**
     * Configure a partitioner for extracting the year from a timestamp field.
     * The UTC timezone is assumed.
     *
     * @param sourceName
     *          The entity field name from which to get values to be
     *          partitioned.
     * @param name
     *          The entity field name of the partition.
     * @return An instance of the builder for method chaining.
     * @since 0.3.0
     */
    public Builder year(String sourceName, String name) {
      fieldPartitioners.add(PartitionFunctions.year(sourceName, name));
      return this;
    }

    /**
     * Configure a partitioner for extracting the year from a timestamp field.
     * The UTC timezone is assumed. The partition entity name will be "year".
     *
     * @param sourceName
     *          The entity field name from which to get values to be
     *          partitioned.
     * @return An instance of the builder for method chaining.
     * @since 0.8.0
     */
    public Builder year(String sourceName) {
      return year(sourceName, "year");
    }

    /**
     * Configure a partitioner for extracting the month from a timestamp field.
     * The UTC timezone is assumed.
     *
     * @param sourceName
     *          The entity field name from which to get values to be
     *          partitioned.
     * @param name
     *          The entity field name of the partition.
     * @return An instance of the builder for method chaining.
     * @since 0.3.0
     */
    public Builder month(String sourceName, String name) {
      fieldPartitioners.add(PartitionFunctions.month(sourceName, name));
      return this;
    }

    /**
     * Configure a partitioner for extracting the month from a timestamp field.
     * The UTC timezone is assumed. The partition entity name will be "month".
     *
     * @param sourceName
     *          The entity field name from which to get values to be
     *          partitioned.
     * @return An instance of the builder for method chaining.
     * @since 0.8.0
     */
    public Builder month(String sourceName) {
      return this.month(sourceName, "month");
    }

    /**
     * Configure a partitioner for extracting the day from a timestamp field.
     * The UTC timezone is assumed.
     *
     * @param sourceName
     *          The entity field name from which to get values to be
     *          partitioned.
     * @param name
     *          The entity field name of the partition.
     * @return An instance of the builder for method chaining.
     * @since 0.3.0
     */
    public Builder day(String sourceName, String name) {
      fieldPartitioners.add(PartitionFunctions.day(sourceName, name));
      return this;
    }

    /**
     * Configure a partitioner for extracting the day from a timestamp field.
     * The UTC timezone is assumed. The partition entity name will be "day".
     *
     * @param sourceName
     *          The entity field name from which to get values to be
     *          partitioned.
     * @return An instance of the builder for method chaining.
     * @since 0.8.0
     */
    public Builder day(String sourceName) {
      return this.day(sourceName, "day");
    }

    /**
     * Configure a partitioner for extracting the hour from a timestamp field.
     * The UTC timezone is assumed.
     *
     * @param sourceName
     *          The entity field name from which to get values to be
     *          partitioned.
     * @param name
     *          The entity field name of the partition.
     * @return An instance of the builder for method chaining.
     * @since 0.3.0
     */
    public Builder hour(String sourceName, String name) {
      fieldPartitioners.add(PartitionFunctions.hour(sourceName, name));
      return this;
    }

    /**
     * Configure a partitioner for extracting the hour from a timestamp field.
     * The UTC timezone is assumed. The partition entity name will be "hour".
     *
     * @param sourceName
     *          The entity field name from which to get values to be
     *          partitioned.
     * @return An instance of the builder for method chaining.
     * @since 0.8.0
     */
    public Builder hour(String sourceName) {
      return this.hour(sourceName, "hour");
    }

    /**
     * Configure a partitioner for extracting the minute from a timestamp field.
     * The UTC timezone is assumed.
     *
     * @param sourceName
     *          The entity field name from which to get values to be
     *          partitioned.
     * @param name
     *          The entity field name of the partition.
     * @return An instance of the builder for method chaining.
     * @since 0.3.0
     */
    public Builder minute(String sourceName, String name) {
      fieldPartitioners.add(PartitionFunctions.minute(sourceName, name));
      return this;
    }

    /**
     * Configure a partitioner for extracting the minute from a timestamp field.
     * The UTC timezone is assumed. The partition entity name will be "minute".
     *
     * @param sourceName
     *          The entity field name from which to get values to be
     *          partitioned.
     * @return An instance of the builder for method chaining.
     * @since 0.8.0
     */
    public Builder minute(String sourceName) {
      return minute(sourceName, "minute");
    }

    /**
     * Configure a partitioner that applies a custom date format to a timestamp
     * field. The UTC timezone is assumed.
     *
     * @param sourceName
     *          The entity field name of the timestamp to format
     * @param name
     *          A name for the partitions created by the format (e.g. "day")
     * @param format
     *          A {@link java.text.SimpleDateFormat} format-string.
     * @return This builder for method chaining.
     * @since 0.9.0
     */
    public Builder dateFormat(String sourceName, String name, String format) {
      fieldPartitioners.add(PartitionFunctions.dateFormat(sourceName, name, format));
      return this;
    }

    /**
     * Build a configured {@link PartitionStrategy} instance.
     *
     * This builder should be considered single use and discarded after a call
     * to this method.
     *
     * @return The configured instance of {@link PartitionStrategy}.
     * @since 0.9.0
     */
    @SuppressWarnings("deprecated")
    public PartitionStrategy build() {
      return new PartitionStrategy(fieldPartitioners);
    }
  }

}
