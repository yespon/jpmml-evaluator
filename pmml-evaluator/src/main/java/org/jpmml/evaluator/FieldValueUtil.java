/*
 * Copyright (c) 2013 Villu Ruusmann
 *
 * This file is part of JPMML-Evaluator
 *
 * JPMML-Evaluator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-Evaluator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-Evaluator.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.evaluator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.common.base.Function;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;
import org.dmg.pmml.DataField;
import org.dmg.pmml.DataType;
import org.dmg.pmml.Field;
import org.dmg.pmml.Interval;
import org.dmg.pmml.InvalidValueTreatmentMethod;
import org.dmg.pmml.MiningField;
import org.dmg.pmml.MissingValueTreatmentMethod;
import org.dmg.pmml.OpType;
import org.dmg.pmml.OutlierTreatmentMethod;
import org.dmg.pmml.PMMLObject;
import org.dmg.pmml.Target;
import org.dmg.pmml.Value;

public class FieldValueUtil {

	private FieldValueUtil(){
	}

	static
	public FieldValue prepareInputValue(Field<?> field, MiningField miningField, Object value){
		DataType dataType = field.getDataType();
		OpType opType = field.getOpType();

		if(dataType == null || opType == null){
			throw new InvalidElementException(field);
		} // End if

		if(Objects.equals(FieldValues.MISSING_VALUE, value) || (value == null)){
			return performMissingValueTreatment(field, miningField);
		} else

		// XXX
		if(value instanceof Collection){
			return createInputValue(field, miningField, value);
		} else

		{
			boolean compatible;

			try {
				value = createInputValue(field, miningField, value);

				compatible = true;
			} catch(IllegalArgumentException | TypeCheckException e){
				compatible = false;
			}

			Value.Property status = getStatus(field, miningField, value, compatible);
			switch(status){
				case VALID:
					return performValidValueTreatment(field, miningField, (FieldValue)value);
				case INVALID:
					return performInvalidValueTreatment(field, miningField, value);
				case MISSING:
					return performMissingValueTreatment(field, miningField);
				default:
					throw new IllegalArgumentException();
			}
		}
	}

	static
	public FieldValue prepareTargetValue(DataField dataField, MiningField miningField, Target target, Object value){
		DataType dataType = dataField.getDataType();
		OpType opType = dataField.getOpType();

		if(dataType == null || opType == null){
			throw new InvalidElementException(dataField);
		} // End if

		if(miningField != null){
			String invalidValueReplacement = miningField.getInvalidValueReplacement();
			if(invalidValueReplacement != null){
				throw new MisplacedAttributeException(miningField, PMMLAttributes.MININGFIELD_INVALIDVALUEREPLACEMENT, invalidValueReplacement);
			}

			String missingValueReplacement = miningField.getMissingValueReplacement();
			if(missingValueReplacement != null){
				throw new MisplacedAttributeException(miningField, PMMLAttributes.MININGFIELD_MISSINGVALUEREPLACEMENT, missingValueReplacement);
			}
		}

		return createTargetValue(dataField, miningField, target, value);
	}

	static
	public FieldValue performValidValueTreatment(Field<?> field, MiningField miningField, FieldValue value){
		OutlierTreatmentMethod outlierTreatmentMethod = miningField.getOutlierTreatment();

		switch(outlierTreatmentMethod){
			case AS_IS:
				return createInputValue(field, miningField, value);
			case AS_MISSING_VALUES:
			case AS_EXTREME_VALUES:
				break;
			default:
				throw new UnsupportedAttributeException(miningField, outlierTreatmentMethod);
		}

		Double lowValue = miningField.getLowValue();
		if(lowValue == null){
			throw new MissingAttributeException(miningField, PMMLAttributes.MININGFIELD_LOWVALUE);
		}

		Double highValue = miningField.getHighValue();
		if(highValue == null){
			throw new MissingAttributeException(miningField, PMMLAttributes.MININGFIELD_HIGHVALUE);
		} // End if

		if((lowValue).compareTo(highValue) > 0){
			throw new InvalidElementException(miningField);
		} // End if

		if(Objects.equals(FieldValues.MISSING_VALUE, value)){
			throw new TypeCheckException(Number.class, null);
		}

		Number numberValue = value.asNumber();

		switch(outlierTreatmentMethod){
			case AS_MISSING_VALUES:
				if((numberValue.doubleValue() < lowValue) || (numberValue.doubleValue() > highValue)){
					return createMissingInputValue(field, miningField);
				}
				break;
			case AS_EXTREME_VALUES:
				if(numberValue.doubleValue() < lowValue){
					return createInputValue(field, miningField, lowValue);
				} else

				if(numberValue.doubleValue() > highValue){
					return createInputValue(field, miningField, highValue);
				}
				break;
			default:
				throw new UnsupportedAttributeException(miningField, outlierTreatmentMethod);
		}

		return createInputValue(field, miningField, value);
	}

	static
	public FieldValue performInvalidValueTreatment(Field<?> field, MiningField miningField, Object value){
		InvalidValueTreatmentMethod invalidValueTreatmentMethod = miningField.getInvalidValueTreatment();
		String invalidValueReplacement = miningField.getInvalidValueReplacement();

		switch(invalidValueTreatmentMethod){
			case AS_IS:
				break;
			case AS_MISSING:
			case RETURN_INVALID:
				if(invalidValueReplacement != null){
					throw new MisplacedAttributeException(miningField, PMMLAttributes.MININGFIELD_INVALIDVALUEREPLACEMENT, invalidValueReplacement);
				}
				break;
			default:
				throw new UnsupportedAttributeException(miningField, invalidValueTreatmentMethod);
		}

		switch(invalidValueTreatmentMethod){
			case RETURN_INVALID:
				throw new InvalidResultException("Field " + PMMLException.formatKey(field.getName()) + " cannot accept user input value " + PMMLException.formatValue(value), miningField);
			case AS_IS:
				if(invalidValueReplacement != null){
					return createInputValue(field, miningField, invalidValueReplacement);
				}
				return createInputValue(field, miningField, value);
			case AS_MISSING:
				return createMissingInputValue(field, miningField);
			default:
				throw new UnsupportedAttributeException(miningField, invalidValueTreatmentMethod);
		}
	}

	static
	public FieldValue performMissingValueTreatment(Field<?> field, MiningField miningField){
		MissingValueTreatmentMethod missingValueTreatmentMethod = miningField.getMissingValueTreatment();

		if(missingValueTreatmentMethod == null){
			missingValueTreatmentMethod = MissingValueTreatmentMethod.AS_IS;
		}

		switch(missingValueTreatmentMethod){
			case AS_IS:
			case AS_MEAN:
			case AS_MODE:
			case AS_MEDIAN:
			case AS_VALUE:
				return createMissingInputValue(field, miningField);
			default:
				throw new UnsupportedAttributeException(miningField, missingValueTreatmentMethod);
		}
	}

	static
	OpType getOpType(Field<?> field, MiningField miningField){
		OpType opType = field.getOpType();

		// "A MiningField overrides a (Data)Field"
		if(miningField != null){
			opType = firstNonNull(miningField.getOpType(), opType);
		}

		return opType;
	}

	static
	OpType getOpType(Field<?> field, MiningField miningField, Target target){
		OpType opType = field.getOpType();

		// "A MiningField overrides a (Data)Field, and a Target overrides a MiningField"
		if(miningField != null){
			opType = firstNonNull(miningField.getOpType(), opType);

			if(target != null){
				opType = firstNonNull(target.getOpType(), opType);
			}
		}

		return opType;
	}

	static
	private Value.Property getStatus(Field<?> field, MiningField miningField, Object value, boolean compatible){

		if(field instanceof DataField){
			DataField dataField = (DataField)field;

			return getStatus(dataField, miningField, value, compatible);
		}

		return (compatible ? Value.Property.VALID : Value.Property.INVALID);
	}

	static
	private Value.Property getStatus(DataField dataField, MiningField miningField, Object value, boolean compatible){
		boolean hasValidSpace = false;

		if(dataField.hasValues()){
			DataType dataType = dataField.getDataType();
			OpType opType = dataField.getOpType();

			if(dataField instanceof HasParsedValueMapping){
				HasParsedValueMapping<?> hasParsedValueMapping = (HasParsedValueMapping<?>)dataField;

				Value pmmlValue = getValue(hasParsedValueMapping, dataType, opType, value);
				if(pmmlValue != null){
					return pmmlValue.getProperty();
				}
			}

			List<Value> pmmlValues = dataField.getValues();
			for(int i = 0, max = pmmlValues.size(); i < max; i++){
				Value pmmlValue = pmmlValues.get(i);

				String stringValue = pmmlValue.getValue();
				if(stringValue == null){
					throw new MissingAttributeException(pmmlValue, PMMLAttributes.VALUE_VALUE);
				}

				boolean equals;

				Value.Property property = pmmlValue.getProperty();
				switch(property){
					case VALID:
						{
							hasValidSpace = true;

							if(!compatible){
								continue;
							} // End if

							if(value instanceof FieldValue){
								FieldValue fieldValue = (FieldValue)value;

								equals = fieldValue.equalsString(stringValue);
							} else

							{
								equals = equals(dataType, value, stringValue);
							}
						}
						break;
					case INVALID:
					case MISSING:
						{
							if(value instanceof FieldValue){
								FieldValue fieldValue = (FieldValue)value;

								equals = equals(dataType, FieldValueUtil.getValue(fieldValue), stringValue);
							} else

							{
								equals = equals(dataType, value, stringValue);
							}
						}
						break;
					default:
						throw new UnsupportedAttributeException(pmmlValue, property);
				}

				if(equals){
					return property;
				}
			}
		} // End if

		if(!compatible){
			return Value.Property.INVALID;
		} // End if

		if(dataField.hasIntervals()){
			PMMLObject locatable = miningField;

			OpType opType = miningField.getOpType();
			if(opType == null){
				locatable = dataField;

				opType = dataField.getOpType();
			}

			switch(opType){
				case CONTINUOUS:
					{
						RangeSet<Double> validRanges = getValidRanges(dataField);

						Double doubleValue;

						if(value instanceof FieldValue){
							FieldValue fieldValue = (FieldValue)value;

							doubleValue = fieldValue.asDouble();
						} else

						{
							throw new IllegalArgumentException();
						}

						// "If intervals are present, then a value that is outside the intervals is considered invalid"
						return (validRanges.contains(doubleValue) ? Value.Property.VALID : Value.Property.INVALID);
					}
				case CATEGORICAL:
				case ORDINAL:
					// "Intervals are not allowed for non-continuous fields"
					throw new InvalidElementException(dataField);
				default:
					throw new UnsupportedAttributeException(locatable, opType);
			}
		}

		// "If a field contains at least one Value element where the value of property is valid, then the set of Value elements completely defines the set of valid values"
		if(hasValidSpace){
			return Value.Property.INVALID;
		}

		// "Any value is valid by default"
		return Value.Property.VALID;
	}

	static
	private FieldValue createInputValue(Field<?> field, MiningField miningField, Object value){

		if(Objects.equals(FieldValues.MISSING_VALUE, value) || (value == null)){
			return FieldValues.MISSING_VALUE;
		}

		DataType dataType = field.getDataType();
		OpType opType = getOpType(field, miningField);

		FieldValue fieldValue = createOrRefine(dataType, opType, value);

		if(field instanceof DataField){
			return enhance((DataField)field, fieldValue);
		}

		return fieldValue;
	}

	static
	private FieldValue createMissingInputValue(Field<?> field, MiningField miningField){
		String missingValueReplacement = miningField.getMissingValueReplacement();

		return createInputValue(field, miningField, missingValueReplacement);
	}

	static
	private FieldValue createTargetValue(Field<?> field, MiningField miningField, Target target, Object value){

		if(Objects.equals(FieldValues.MISSING_VALUE, value) || (value == null)){
			return FieldValues.MISSING_VALUE;
		}

		DataType dataType = field.getDataType();
		OpType opType = getOpType(field, miningField, target);

		FieldValue fieldValue = createOrRefine(dataType, opType, value);

		if(field instanceof DataField){
			return enhance((DataField)field, fieldValue);
		}

		return fieldValue;
	}

	static
	public FieldValue create(Object value){

		if(value == null){
			return FieldValues.MISSING_VALUE;
		}

		DataType dataType;

		if(value instanceof Collection){
			dataType = TypeUtil.getDataType((Collection<?>)value);
		} else

		{
			dataType = TypeUtil.getDataType(value);
		}

		OpType opType = TypeUtil.getOpType(dataType);

		return createInternal(dataType, opType, value);
	}

	static
	public FieldValue create(Field<?> field, Object value){

		if(value == null){
			return FieldValues.MISSING_VALUE;
		}

		DataType dataType = field.getDataType();
		if(dataType == null){

			if(value instanceof Collection){
				dataType = TypeUtil.getDataType((Collection<?>)value);
			} else

			{
				dataType = TypeUtil.getDataType(value);
			}
		} else

		{
			if(value instanceof Collection){
				// Ignored
			} else

			{
				value = TypeUtil.parseOrCast(dataType, value);
			}
		}

		OpType opType = field.getOpType();
		if(opType == null){
			opType = TypeUtil.getOpType(dataType);
		}

		FieldValue result = createInternal(dataType, opType, value);

		if(field instanceof DataField){
			return enhance((DataField)field, result);
		}

		return result;
	}

	static
	public FieldValue create(DataType dataType, OpType opType, Object value){

		if(value == null){
			return FieldValues.MISSING_VALUE;
		} // End if

		if(dataType == null || opType == null){
			throw new IllegalArgumentException();
		} // End if

		if(value instanceof Collection){
			// Ignored
		} else

		{
			value = TypeUtil.parseOrCast(dataType, value);
		}

		return createInternal(dataType, opType, value);
	}

	static
	public List<FieldValue> createAll(final DataType dataType, final OpType opType, List<?> values){
		Function<Object, FieldValue> function = new Function<Object, FieldValue>(){

			@Override
			public FieldValue apply(Object value){
				return create(dataType, opType, value);
			}
		};

		return Lists.transform(values, function);
	}

	static
	public FieldValue refine(Field<?> field, FieldValue value){
		FieldValue result = refine(field.getDataType(), field.getOpType(), value);

		if((field instanceof DataField) && (result != value)){
			return enhance((DataField)field, result);
		}

		return result;
	}

	static
	public FieldValue refine(DataType dataType, OpType opType, FieldValue value){

		if(Objects.equals(FieldValues.MISSING_VALUE, value)){
			return FieldValues.MISSING_VALUE;
		}

		DataType valueDataType = value.getDataType();
		OpType valueOpType = value.getOpType();

		DataType refinedDataType = firstNonNull(dataType, valueDataType);
		OpType refinedOpType = firstNonNull(opType, valueOpType);

		if((refinedDataType).equals(valueDataType)){

			if((refinedOpType).equals(valueOpType)){
				return value;
			}

			return createInternal(refinedDataType, refinedOpType, value.getValue());
		}

		return create(refinedDataType, refinedOpType, value.getValue());
	}

	static
	private FieldValue createOrRefine(DataType dataType, OpType opType, Object value){

		if(Objects.equals(FieldValues.MISSING_VALUE, value) || (value == null)){
			return FieldValues.MISSING_VALUE;
		} // End if

		if(value instanceof FieldValue){
			FieldValue fieldValue = (FieldValue)value;

			return refine(dataType, opType, fieldValue);
		} else

		{
			return create(dataType, opType, value);
		}
	}

	static
	private FieldValue enhance(DataField dataField, FieldValue value){

		if(value instanceof OrdinalValue){
			OrdinalValue ordinalValue = (OrdinalValue)value;

			ordinalValue.setOrdering(getOrdering(dataField, ordinalValue.getDataType()));
		}

		return value;
	}

	static
	private FieldValue createInternal(DataType dataType, OpType opType, Object value){

		switch(opType){
			case CONTINUOUS:
				return ContinuousValue.create(dataType, value);
			case CATEGORICAL:
				return CategoricalValue.create(dataType, value);
			case ORDINAL:
				return OrdinalValue.create(dataType, value);
			default:
				throw new IllegalArgumentException();
		}
	}

	static
	public Object getValue(FieldValue value){
		return (value != null ? value.getValue() : null);
	}

	static
	public <V> V getValue(Class<? extends V> clazz, FieldValue value){
		return TypeUtil.cast(clazz, getValue(value));
	}

	static
	public Value getValidValue(TargetField targetField, Object value){
		return getValidValue(targetField.getDataField(), value);
	}

	static
	public Value getValidValue(DataField dataField, Object value){

		if(value == null){
			return null;
		} // End if

		if(dataField.hasValues()){
			DataType dataType = dataField.getDataType();
			OpType opType = dataField.getOpType();

			value = TypeUtil.parseOrCast(dataType, value);

			if(dataField instanceof HasParsedValueMapping){
				HasParsedValueMapping<?> hasParsedValueMapping = (HasParsedValueMapping<?>)dataField;

				FieldValue fieldValue = createInternal(dataType, opType, value);

				Value pmmlValue = getValue(hasParsedValueMapping, dataType, opType, fieldValue);
				if(pmmlValue != null && (Value.Property.VALID).equals(pmmlValue.getProperty())){
					return pmmlValue;
				}

				return null;
			}

			List<Value> pmmlValues = dataField.getValues();
			for(int i = 0, max = pmmlValues.size(); i < max; i++){
				Value pmmlValue = pmmlValues.get(i);

				String stringValue = pmmlValue.getValue();
				if(stringValue == null){
					throw new MissingAttributeException(pmmlValue, PMMLAttributes.VALUE_VALUE);
				}

				Value.Property property = pmmlValue.getProperty();
				switch(property){
					case VALID:
						{
							boolean equals = equals(dataType, value, stringValue);

							if(equals){
								return pmmlValue;
							}
						}
						break;
					case INVALID:
					case MISSING:
						break;
					default:
						throw new UnsupportedAttributeException(pmmlValue, property);
				}
			}
		}

		return null;
	}

	static
	private Value getValue(HasParsedValueMapping<?> hasParsedValueMapping, DataType dataType, OpType opType, Object value){
		FieldValue fieldValue;

		try {
			fieldValue = createOrRefine(dataType, opType, value);
		} catch(IllegalArgumentException | TypeCheckException e){
			return null;
		}

		Map<FieldValue, ?> fieldValues = hasParsedValueMapping.getValueMapping(dataType, opType);

		return (Value)fieldValues.get(value);
	}

	static
	public List<Value> getValidValues(DataField dataField){

		if(dataField.hasValues()){
			List<Value> pmmlValues = dataField.getValues();

			List<Value> result = new ArrayList<>(pmmlValues.size());

			for(int i = 0, max = pmmlValues.size(); i < max; i++){
				Value pmmlValue = pmmlValues.get(i);

				Value.Property property = pmmlValue.getProperty();
				switch(property){
					case VALID:
						result.add(pmmlValue);
						break;
					case INVALID:
					case MISSING:
						break;
					default:
						throw new UnsupportedAttributeException(pmmlValue, property);
				}
			}

			return result;
		}

		return Collections.emptyList();
	}

	static
	public List<String> getTargetCategories(TargetField targetField){
		return getTargetCategories(targetField.getDataField());
	}

	static
	public List<String> getTargetCategories(DataField dataField){
		return CacheUtil.getValue(dataField, FieldValueUtil.targetCategoryCache);
	}

	static
	public RangeSet<Double> getValidRanges(DataField dataField){
		return CacheUtil.getValue(dataField, FieldValueUtil.validRangeCache);
	}

	static
	private boolean equals(DataType dataType, Object value, String referenceValue){

		try {
			return (TypeUtil.parseOrCast(dataType, value)).equals(TypeUtil.parse(dataType, referenceValue));
		} catch(IllegalArgumentException | TypeCheckException e){

			// The String representation of invalid or missing values (eg. "N/A") may not be parseable to the requested representation
			try {
				return (TypeUtil.parseOrCast(DataType.STRING, value)).equals(referenceValue);
			} catch(TypeCheckException tce){
				// Ignored
			}

			throw e;
		}
	}

	static
	private <V> V firstNonNull(V value, V defaultValue){

		if(value != null){
			return value;
		}

		return defaultValue;
	}

	static
	private RangeSet<Double> parseValidRanges(DataField dataField){
		RangeSet<Double> result = TreeRangeSet.create();

		List<Interval> intervals = dataField.getIntervals();
		for(Interval interval : intervals){
			Range<Double> range = DiscretizationUtil.toRange(interval);

			result.add(range);
		}

		return result;
	}

	static
	private List<?> getOrdering(DataField dataField, final DataType dataType){
		List<Value> values = getValidValues(dataField);
		if(values.isEmpty()){
			return null;
		}

		Function<Value, Object> function = new Function<Value, Object>(){

			@Override
			public Object apply(Value value){
				String stringValue = value.getValue();
				if(stringValue == null){
					throw new MissingAttributeException(value, PMMLAttributes.VALUE_VALUE);
				}

				return TypeUtil.parse(dataType, stringValue);
			}
		};

		return Lists.newArrayList(Iterables.transform(values, function));
	}

	private static final LoadingCache<DataField, List<String>> targetCategoryCache = CacheUtil.buildLoadingCache(new CacheLoader<DataField, List<String>>(){

		@Override
		public List<String> load(DataField dataField){
			List<Value> values = getValidValues(dataField);

			Function<Value, String> function = new Function<Value, String>(){

				@Override
				public String apply(Value value){
					String stringValue = value.getValue();
					if(stringValue == null){
						throw new MissingAttributeException(value, PMMLAttributes.VALUE_VALUE);
					}

					return stringValue;
				}
			};

			return ImmutableList.copyOf(Iterables.transform(values, function));
		}
	});

	private static final LoadingCache<DataField, RangeSet<Double>> validRangeCache = CacheUtil.buildLoadingCache(new CacheLoader<DataField, RangeSet<Double>>(){

		@Override
		public RangeSet<Double> load(DataField dataField){
			return ImmutableRangeSet.copyOf(parseValidRanges(dataField));
		}
	});
}