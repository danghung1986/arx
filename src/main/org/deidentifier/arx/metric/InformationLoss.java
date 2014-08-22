/*
 * ARX: Powerful Data Anonymization
 * Copyright (C) 2012 - 2014 Florian Kohlmayer, Fabian Prasser
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.deidentifier.arx.metric;

import java.io.Serializable;

/**
 * This class implements an abstract base class for information loss
 * 
 * @author Fabian Prasser
 * @author Florian Kohlmayer
 */
public abstract class InformationLoss<T> implements Comparable<InformationLoss<?>>, Serializable {

    private static final long serialVersionUID = -5347658129539223333L;
    
    private final InformationLoss<T> lowerBound;
    
    protected InformationLoss(InformationLoss<T> lowerBound) {
        this.lowerBound = lowerBound;
    }
    
    public abstract T getValue();
    
    /**
     * Returns the value relative to the other instance
     * @param other
     * @return
     */
    public abstract double relativeTo(InformationLoss<?> min, InformationLoss<?> max);
    
    /**
     * Compares the loss to the other
     * @param other
     * @return
     */
    public abstract int compareTo(InformationLoss<?> other);

    /**
     * Returns a string representation
     * 
     * @return
     */
    public abstract String toString();

    /**
     * Retains the maximum of this and other
     * 
     * @param other
     */
    public abstract void max(InformationLoss<?> other);

    /**
     * Retains the minimum of this and other
     * 
     * @param other
     */
    public abstract void min(InformationLoss<?> other);

    /**
     * Returns a clone of this object
     */
    @Override
    public abstract InformationLoss<T> clone();
    
    /**
     * Returns a monotonic lower bound
     * @return
     */
    public InformationLoss<T> getLowerBound(){
        return this.lowerBound;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((lowerBound == null) ? 0 : lowerBound.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        InformationLoss<?> other = (InformationLoss<?>) obj;
        if (lowerBound == null) {
            if (other.lowerBound != null) return false;
        } else if (!lowerBound.equals(other.lowerBound)) return false;
        return true;
    }
}
